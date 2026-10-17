import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

function App() {
  const [status, setStatus] = useState('Checking connection…')
  const [checking, setChecking] = useState(false)

  async function checkConnection() {
    setChecking(true)
    setStatus('Checking connection…')
    try {
      const response = await fetch('/api/health', { signal: AbortSignal.timeout(5000) })
      if (!response.ok) throw new Error('Service unavailable')
      const health = await response.json()
      if (health.status !== 'ok' || health.service !== 'click-to-batch') {
        throw new Error('Unexpected service')
      }
      setStatus('Backend connected')
    } catch {
      setStatus('Backend offline — start the Backend task in VS Code')
    } finally {
      setChecking(false)
    }
  }

  useEffect(() => { void checkConnection() }, [])

  return (
    <main>
      <header><span className="mark" aria-hidden="true">↗</span> CLICK TO BATCH</header>
      <p className="eyebrow">REINVENT THE WHEEL · DEVELOPMENT SCAFFOLD</p>
      <h1>One workflow.<br />A whole batch.</h1>
      <p className="intro">A local companion for repeatable Fiji and ImageJ analysis.</p>
      <section aria-labelledby="connection-title">
        <div>
          <h2 id="connection-title">Workspace connection</h2>
          <p role="status">{status}</p>
        </div>
        <button onClick={() => void checkConnection()} disabled={checking}>
          {checking ? 'Checking…' : 'Check connection'}
        </button>
      </section>
      <p className="next">Next: validate a Fiji macro, preview its results, then run a batch.</p>
      <footer>Fiji execution, image workflows and voice input are planned; this starter verifies the development setup.</footer>
    </main>
  )
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
