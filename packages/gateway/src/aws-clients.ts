import type { AwsCredentialIdentity } from '@aws-sdk/types';

/** Keep one warm (TLS keep-alive) SDK client per credential identity instead of a new
 * connection per request; each handshake to us-east-1 costs ~0.3 s from Europe.
 * A credential rotation replaces and destroys the previous client. */
export function reusableClient<T extends { destroy(): void }>(create: (credentials: AwsCredentialIdentity) => T) {
  let current: { key: string; client: T } | undefined;
  return (credentials: AwsCredentialIdentity): T => {
    const key = `${credentials.accessKeyId}\n${credentials.sessionToken ?? ''}\n${credentials.secretAccessKey}`;
    if (current?.key !== key) { current?.client.destroy(); current = { key, client: create(credentials) }; }
    return current.client;
  };
}
