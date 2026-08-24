import { useRef, useState } from 'react'
import { money, postJson } from './api'
import type { ChatMessage, ToolCall, Transcript } from './types'

/**
 * The autonomous agent, made legible: every tool call is shown as an
 * audit row, every agent/system remark as a chat bubble. When consent
 * sits at REQUESTED the human decides - approve and the run resumes,
 * decline and the purchase dies there.
 */
export default function AgentChat() {
  const [transcript, setTranscript] = useState<Transcript | null>(null)
  const [goal, setGoal] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const logRef = useRef<HTMLDivElement>(null)

  function show(t: Transcript) {
    setTranscript(t)
    setGoal('')
    requestAnimationFrame(() =>
      logRef.current?.scrollTo({ top: logRef.current.scrollHeight }),
    )
  }

  async function act(fn: () => Promise<Transcript>) {
    setBusy(true)
    setError(null)
    try {
      show(await fn())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The agent hit a wall')
    } finally {
      setBusy(false)
    }
  }

  const start = () =>
    act(async () => postJson<Transcript>('/api/v1/agent/sessions', { goal }))
  const run = (): Promise<Transcript> =>
    postJson<Transcript>(
      `/api/v1/agent/sessions/${transcript!.sessionId}/run`,
    )
  const confirmConsent = (): Promise<Transcript> =>
    postJson<Transcript>(
      `/api/v1/agent/sessions/${transcript!.sessionId}/consent/confirm`,
    ).then(run)
  const cancelConsent = (): Promise<Transcript> =>
    postJson<Transcript>(
      `/api/v1/agent/sessions/${transcript!.sessionId}/consent/cancel`,
    )

  return (
    <section className="agent">
      <h2>Your shopping agent</h2>
      <p className="muted">
        It can browse, cart and checkout on your behalf — but money only
        moves when YOU approve. Every action it takes is logged below.
      </p>

      <form
        className="goal"
        onSubmit={(e) => {
          e.preventDefault()
          if (goal.trim()) void start()
        }}
      >
        <input
          placeholder='e.g. "buy me running shoes under ₹5,000"'
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
          disabled={busy}
        />
        <button disabled={busy || !goal.trim()}>
          {busy ? 'Working…' : 'Start'}
        </button>
      </form>
      {error && <p className="error">{error}</p>}

      {transcript && (
        <>
          <ConsentBanner
            transcript={transcript}
            busy={busy}
            onConfirm={confirmConsent}
            onCancel={cancelConsent}
            onResume={run}
          />

          <div className="chat-log" ref={logRef}>
            {transcript.messages.map((m, i) => (
              <Bubble key={i} message={m} />
            ))}
          </div>

          <details className="trace" open>
            <summary>
              Tool-call audit trail ({transcript.toolCalls.length})
            </summary>
            <ol>
              {transcript.toolCalls.map((c, i) => (
                <ToolRow key={i} call={c} />
              ))}
            </ol>
          </details>

          <footer className="spend">
            Session spend so far:{' '}
            <strong>{money(transcript.reservedSpend)}</strong> · consent{' '}
            <span className={`badge c-${transcript.consentState.toLowerCase()}`}>
              {transcript.consentState}
            </span>
          </footer>
        </>
      )}
    </section>
  )
}

function ConsentBanner({
  transcript,
  busy,
  onConfirm,
  onCancel,
  onResume,
}: {
  transcript: Transcript
  busy: boolean
  onConfirm: () => void
  onCancel: () => void
  onResume: () => void
}) {
  const last = transcript.toolCalls[transcript.toolCalls.length - 1]
  const crashed =
    last != null && last.status === 'ERROR' && last.tool === 'plan_next_step'

  if (transcript.consentState === 'REQUESTED') {
    return (
      <div className="banner">
        <div>
          <strong>The agent asks permission to spend.</strong>
          <p className="muted">
            Review its trace below, then decide. Approving allows exactly one
            payment initiation; it cannot ask again silently.
          </p>
        </div>
        <div className="actions">
          <button className="primary" disabled={busy} onClick={onConfirm}>
            ✓ Approve &amp; continue
          </button>
          <button className="danger" disabled={busy} onClick={onCancel}>
            ✕ Decline
          </button>
        </div>
      </div>
    )
  }

  const resumable =
    ['NONE', 'CONFIRMED', 'CANCELLED'].includes(transcript.consentState) &&
    !busy
  if (!resumable && !crashed) return null

  return (
    <div className="banner subtle">
      <p className="muted">
        {crashed
          ? 'The planner failed mid-run; you can try continuing.'
          : `Session state: ${transcript.consentState}.`}
      </p>
      {!busy && (
        <button onClick={onResume}>▶ Continue session</button>
      )}
    </div>
  )
}

function Bubble({ message }: { message: ChatMessage }) {
  const cls =
    message.role === 'USER'
      ? 'user'
      : message.role === 'AGENT'
        ? 'agent'
        : 'system'
  return (
    <div className={`bubble ${cls}`}>
      <span className="who">{message.role.toLowerCase()}</span>
      <p>{message.content}</p>
    </div>
  )
}

function ToolRow({ call }: { call: ToolCall }) {
  const detail = call.error ?? JSON.stringify(call.resultSummary ?? {})
  return (
    <li className={`tool ${call.status.toLowerCase()}`}>
      <code>{call.tool}</code>
      <span className={`badge ${call.status.toLowerCase()}`}>
        {call.status}
      </span>
      <span className="detail">{detail}</span>
    </li>
  )
}
