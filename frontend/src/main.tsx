import { StrictMode, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

type Action = { operation: string; sigma?: number | null; level?: number | null }
type Event = { kind: string; message: string; stage: number | null; time: string }
type Job = { id: string; image_id: string; status: string; latest_stage: number | null; events: Event[];
  macro: string; measurements: { stage: string; area: string; mean: string; min: string; max: string }[] }

async function api(path: string, options?: RequestInit) {
  const response = await fetch(`/api${path}`, options)
  if (!response.ok) {
    const error = await response.json().catch(() => ({ detail: response.statusText }))
    throw new Error(typeof error.detail === 'string' ? error.detail : JSON.stringify(error.detail))
  }
  return response.json()
}
const post = (value: unknown): RequestInit => ({ method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) })

function App() {
  const [text, setText] = useState('invert the image then measure the image')
  const [actions, setActions] = useState<Action[]>([])
  const [macro, setMacro] = useState('')
  const [imageId, setImageId] = useState('demo')
  const [job, setJob] = useState<Job | null>(null)
  const [jobId, setJobId] = useState<string | null>(null)
  const [stage, setStage] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [recording, setRecording] = useState(false)
  const [message, setMessage] = useState('Connecting to local workspace…')
  const [error, setError] = useState('')
  const recorder = useRef<MediaRecorder | null>(null)
  const recordingTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const running = Boolean(jobId && (!job || ['queued', 'running'].includes(job.status)))
  const locked = busy || running || recording

  useEffect(() => {
    api('/health').then(() => setMessage('Local workspace connected')).catch(e => setError(e.message))
    return () => {
      if (recordingTimer.current) clearTimeout(recordingTimer.current)
      recorder.current?.stream.getTracks().forEach(track => track.stop())
    }
  }, [])

  useEffect(() => {
    if (!jobId) return
    let active = true
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const current = await api(`/jobs/${jobId}`) as Job
        if (!active) return
        setJob(current)
        setMessage(`Workflow ${current.status}`)
        if (['queued', 'running'].includes(current.status)) timer = setTimeout(poll, 350)
      } catch (e) {
        if (active) { setError((e as Error).message); setJobId(null) }
      }
    }
    void poll()
    return () => { active = false; clearTimeout(timer) }
  }, [jobId])

  async function preview(value = text) {
    setBusy(true); setError(''); setActions([]); setMacro('')
    try {
      const result = await api('/commands/preview', post({ text: value }))
      setActions(result.actions); setMacro(result.macro)
      setMessage('Command ready for review. Run it when you are ready.')
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  async function run(replay = false) {
    setBusy(true); setError(''); setStage(null)
    try {
      const result = replay && job ? await api(`/jobs/${job.id}/replay`, post({}))
        : await api('/jobs', post({ actions, image_id: imageId }))
      setJob(null); setJobId(result.id)
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  async function transcribe(blob: Blob) {
    setBusy(true); setError(''); setMessage('Transcribing locally…'); setActions([]); setMacro('')
    try {
      const result = await api('/speech/transcribe', { method: 'POST', body: blob,
        headers: { 'Content-Type': 'application/octet-stream' } })
      setText(result.text)
      if (!result.text) throw new Error('No speech detected. Try another clip.')
      await preview(result.text)
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  async function toggleRecording() {
    if (recording) { recorder.current?.stop(); return }
    let stream: MediaStream | undefined
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const next = new MediaRecorder(stream)
      const chunks: BlobPart[] = []
      next.ondataavailable = event => { if (event.data.size) chunks.push(event.data) }
      next.onstop = () => {
        if (recordingTimer.current) clearTimeout(recordingTimer.current)
        next.stream.getTracks().forEach(track => track.stop())
        setRecording(false)
        void transcribe(new Blob(chunks, { type: next.mimeType }))
      }
      recorder.current = next; next.start(); setRecording(true); setError('')
      setMessage('Listening… stop when finished (maximum 25 seconds).')
      recordingTimer.current = setTimeout(() => { if (next.state === 'recording') next.stop() }, 25000)
    } catch (e) {
      stream?.getTracks().forEach(track => track.stop())
      setError(`Microphone unavailable: ${(e as Error).message}. You can upload an audio clip.`)
    }
  }

  async function upload(file?: File) {
    if (!file) return
    setBusy(true); setError('')
    try {
      const result = await api('/images', { method: 'POST', body: file,
        headers: { 'Content-Type': 'application/octet-stream' } })
      setImageId(result.image_id); setJob(null); setJobId(null); setStage(null)
      setMessage('Image loaded. Review a command to begin.')
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  const selectedStage = stage ?? job?.latest_stage
  const artifact = (name: string) => `/api/jobs/${job!.id}/artifacts/${name}`
  return <main>
    <header><span className="mark" aria-hidden="true">F</span><strong>Fidelis</strong>
      <span className="badge">Integration workbench</span></header>
    <h1>Give an instruction.<br /><span>See the image change.</span></h1>
    <p className="intro">Real Fiji / ImageJ processing, with a visible record of every supported action.</p>
    <div className="status" role="status"><span className={locked ? 'dot active' : 'dot'} />{message}</div>
    {error && <p className="error" role="alert">{error}</p>}
    <div className="workspace">
      <section className="controls" aria-labelledby="command-heading">
        <h2 id="command-heading">Instruction</h2>
        <label htmlFor="command">Type a command or use your voice</label>
        <textarea id="command" value={text} disabled={locked} onChange={e => {
          setText(e.target.value); setActions([]); setMacro('')
        }} />
        <p className="hint">Try “invert the image”, “blur sigma 2”, “threshold at 128” or “measure the image”. Combine with “then”.</p>
        <div className="buttons">
          <button disabled={locked} onClick={() => void preview()}>Review command</button>
          <button className="secondary" disabled={busy || running} onClick={() => void toggleRecording()}>
            {recording ? 'Stop recording' : 'Use microphone'}</button>
        </div>
        <label className="file">Or upload audio (up to 30 seconds)
          <input type="file" accept="audio/*" disabled={locked} onChange={e => {
            const file = e.target.files?.[0]; if (file) void transcribe(file); e.target.value = ''
          }} /></label>
        <h2>Recorded workflow</h2>
        {actions.length ? <ol>{actions.map((action, i) => <li key={i}>
          {action.operation}{action.sigma != null ? ` · sigma ${action.sigma}` : ''}
          {action.level != null ? ` · level ${action.level}` : ''}</li>)}</ol>
          : <p className="hint">Review a command to see the exact actions before running.</p>}
        <button disabled={locked || !actions.length} onClick={() => void run()}>Run in Fiji</button>
        {macro && <details><summary>ImageJ macro preview</summary><pre>{macro}</pre></details>}
      </section>
      <section className="viewer" aria-labelledby="image-heading">
        <div className="section-head"><h2 id="image-heading">Image workspace</h2><span>8-bit grayscale</span></div>
        <div className="images">
          <figure><img src={`/api/images/${job?.image_id ?? imageId}`} alt="Original input image" /><figcaption>Original</figcaption></figure>
          <figure><img src={job && selectedStage != null ? artifact(`stage-${selectedStage}.png`) : `/api/images/${imageId}`}
            alt={selectedStage != null ? `ImageJ output at stage ${selectedStage}` : 'Awaiting ImageJ output'} />
            <figcaption>{selectedStage != null ? `Stage ${selectedStage}` : 'Ready to process'}</figcaption></figure>
        </div>
        <label className="file">Load your own grayscale PNG (up to 4 million pixels)
          <input type="file" accept="image/png" disabled={locked} onChange={e => { void upload(e.target.files?.[0]); e.target.value = '' }} /></label>
        <button className="secondary" disabled={locked} onClick={() => {
          setImageId('demo'); setJob(null); setJobId(null); setStage(null)
        }}>Use synthetic demo</button>
        {job && <>
          <h2>Execution timeline</h2>
          <ol className="timeline" aria-live="polite">{job.events.map((event, i) => <li key={i}>
            <span>{event.message}</span>{event.stage !== null && <button className="small secondary"
              onClick={() => setStage(event.stage)}>View stage {event.stage}</button>}</li>)}</ol>
          {job.measurements.length > 0 && <table><caption>ImageJ measurements · pixel units</caption>
            <thead><tr><th>Stage</th><th>Area</th><th>Mean</th><th>Min</th><th>Max</th></tr></thead>
            <tbody>{job.measurements.map(row => <tr key={row.stage}><td>{row.stage}</td><td>{row.area}</td>
              <td>{row.mean}</td><td>{row.min}</td><td>{row.max}</td></tr>)}</tbody></table>}
          <div className="buttons downloads">
            <a href={artifact('recording.ijm')} download="fidelis-recording.ijm">Download macro</a>
            {job.status === 'completed' && <><a href={artifact('measurements.csv')} download>Measurements</a>
              <button className="secondary" disabled={locked} onClick={() => void run(true)}>Replay recording</button></>}
            {job.status === 'failed' && <a href={artifact('fiji.log')}>Execution log</a>}
          </div>
        </>}
      </section>
    </div>
    <footer>Fidelis is a companion to upstream Fiji, ImageJ and faster-whisper. This workbench demonstrates four operations.
      Native Fiji window recording, general macro import, batch datasets and final interface design are still planned.</footer>
  </main>
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
