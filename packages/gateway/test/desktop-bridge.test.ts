import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DesktopBridge, DesktopBridgeError, DESKTOP_TIMEOUTS_MS, withDesktop, parseDesktopOperation, type DesktopPeer } from '../src/desktop-bridge.js';
import { desktopTools, desktopToolNames } from '../src/desktop-tools.js';
import { tools } from '../src/tools.js';
import AjvModule from 'ajv';

class FakeSocket implements DesktopPeer {
  readyState = 1;
  sent: Array<{ type: string; requestId: string; op: string; args: Record<string, unknown> }> = [];
  send(data: string) { this.sent.push(JSON.parse(data)); }
}

test('desktop bridge rejects clearly when no companion is connected', async () => {
  const bridge = new DesktopBridge();
  await assert.rejects(bridge.request('inspect'), (error: DesktopBridgeError) => error.code === 'no_companion' && /No Luma companion is connected/.test(error.message));
  const closed = new FakeSocket(); closed.readyState = 3; bridge.attach(closed);
  assert.equal(bridge.connected, false);
  await assert.rejects(bridge.request('act', { id: 'x', action: 'press' }), (error: DesktopBridgeError) => error.code === 'no_companion');
  assert.equal(closed.sent.length, 0);
});

test('desktop bridge correlates requestId with the companion reply and unwraps result or error', async () => {
  const bridge = new DesktopBridge(); const socket = new FakeSocket(); bridge.attach(socket);
  const pending = bridge.request('inspect', { apps: ['frontmost'] });
  assert.equal(socket.sent.length, 1);
  const [sent] = socket.sent;
  assert.equal(sent.type, 'desktop_request'); assert.equal(sent.op, 'inspect'); assert.deepEqual(sent.args, { apps: ['frontmost'] });
  assert.match(sent.requestId, /^[0-9a-f-]{36}$/);
  assert.equal(bridge.handle({ type: 'desktop_response', requestId: 'someone-else', result: { trusted: true } }), true, 'unknown ids are consumed, not thrown');
  assert.equal(bridge.handle({ type: 'text', text: 'hi' }), false);
  assert.equal(bridge.pendingCount, 1);
  bridge.handle({ type: 'desktop_response', requestId: sent.requestId, result: { trusted: true, windows: [] } });
  assert.deepEqual(await pending, { trusted: true, windows: [] });
  assert.equal(bridge.pendingCount, 0);

  const failing = bridge.request('act', { id: '1/w0/2', action: 'press' });
  bridge.handle({ type: 'desktop_response', requestId: socket.sent[1].requestId, error: 'Element 1/w0/2 was not seen in the last 30 s.' });
  await assert.rejects(failing, (error: DesktopBridgeError) => error.code === 'companion' && /not seen/.test(error.message));
});

test('desktop bridge times out per operation and fails pending requests when the companion detaches', async () => {
  assert.equal(DESKTOP_TIMEOUTS_MS.inspect, 10_000); assert.equal(DESKTOP_TIMEOUTS_MS.act, 15_000);
  const bridge = new DesktopBridge(); const socket = new FakeSocket(); bridge.attach(socket);
  DESKTOP_TIMEOUTS_MS.inspect = 20;
  try {
    await assert.rejects(bridge.request('inspect'), (error: DesktopBridgeError) => error.code === 'timeout' && /within 0.02 s/.test(error.message));
  } finally { DESKTOP_TIMEOUTS_MS.inspect = 10_000; }
  const orphaned = bridge.request('key', { keys: ['return'] });
  bridge.detach(socket);
  await assert.rejects(orphaned, (error: DesktopBridgeError) => error.code === 'no_companion' && /disconnected/.test(error.message));
  assert.equal(bridge.pendingCount, 0);
  const controller = new AbortController();
  const second = new FakeSocket(); bridge.attach(second);
  const aborted = bridge.request('type', { text: 'x' }, controller.signal); controller.abort();
  await assert.rejects(aborted, (error: DesktopBridgeError) => error.code === 'aborted');
});

test('desktop bridge prefers the most recently attached open companion', async () => {
  const bridge = new DesktopBridge(); const first = new FakeSocket(); const second = new FakeSocket();
  bridge.attach(first); bridge.attach(second); bridge.attach(first);
  void bridge.request('click', { x: 1, y: 2 }).catch(() => undefined);
  assert.equal(first.sent.length, 1); assert.equal(second.sent.length, 0);
  bridge.detach(first);
  void bridge.request('click', { x: 1, y: 2 }).catch(() => undefined);
  assert.equal(second.sent.length, 1);
});

test('withDesktop routes desktop_* operations to the companion and everything else to the inner adapter', async () => {
  const bridge = new DesktopBridge(); const socket = new FakeSocket(); bridge.attach(socket);
  const calls: string[] = [];
  const adapter = withDesktop({ execute: async operation => { calls.push(operation); return { contextKey: 'k' }; } }, bridge);
  assert.deepEqual(await adapter.execute('context'), { contextKey: 'k' });
  const inspect = adapter.execute('desktop_inspect', {}, { expectedContext: 'k' });
  bridge.handle({ type: 'desktop_response', requestId: socket.sent[0].requestId, result: { trusted: true, windows: [] } });
  assert.deepEqual(await inspect, { trusted: true, windows: [] });
  const untrusted = adapter.execute('desktop_key', { keys: ['return'] });
  bridge.handle({ type: 'desktop_response', requestId: socket.sent[1].requestId, result: { trusted: false, error: 'Luma is not trusted for Accessibility.' } });
  await assert.rejects(untrusted, /not trusted for Accessibility/);
  assert.deepEqual(calls, ['context']);
  assert.equal(parseDesktopOperation('desktop_act'), 'act'); assert.equal(parseDesktopOperation('desktop_nope'), undefined); assert.equal(parseDesktopOperation('ui'), undefined);
});

test('desktop tool definitions are valid, distinct from in-app tools and steer the model to inspect before acting', () => {
  const Ajv = AjvModule.default ?? AjvModule; const ajv = new Ajv({ allErrors: true, strict: true });
  assert.deepEqual([...desktopToolNames], ['qupath_desktop_inspect', 'qupath_desktop_act', 'qupath_desktop_type', 'qupath_desktop_key', 'qupath_desktop_click']);
  for (const tool of desktopTools) {
    assert.equal(tools.filter(existing => existing.name === tool.name).length, 1, `${tool.name} must appear exactly once in the merged tool list`);
    assert.equal(parseDesktopOperation(tool.operation!), tool.name.replace('qupath_desktop_', ''));
    assert.match(tool.description, /qupath_ui/);
    const validate = ajv.compile(tool.inputSchema); assert.equal(typeof validate, 'function');
  }
  const [inspect, act, type, key, click] = desktopTools;
  assert.equal(inspect.risk, 'read'); for (const tool of [act, type, key, click]) assert.equal(tool.risk, 'code');
  assert.match(act.description, /30 s/); assert.match(type.description, /cmd\+shift\+g/);
  const validateAct = ajv.compile(act.inputSchema);
  assert.equal(validateAct({ id: '1/w0/2', action: 'press' }), true);
  assert.equal(validateAct({ id: '1/w0/2', action: 'explode' }), false);
  assert.equal(ajv.compile(key.inputSchema)({ keys: [] }), false);
  assert.equal(ajv.compile(click.inputSchema)({ x: 1 }), false);
});
