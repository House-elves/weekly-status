// weekly-status's tab on Elf Desk, and its card on the dashboard. It runs in the desk's sandboxed frame: it reads
// the drafts this elf saved (~/.config/weekly-status/runs) and asks the desk to send, through the bridge.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, calendarText, copyText, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

const ID = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.json$/;

/** When a draft was made, from its file name (the elf's local time). */
function atOf(file) {
    const m = ID.exec(file);
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6]) : null;
}

/** The drafts, newest first: [{file, id, at}]. */
async function drafts() {
    return (await elf.list('runs')).filter(f => ID.test(f.name)).slice(0, 12)
        .map(f => ({ file: f.name, id: f.name.slice(0, -5), at: atOf(f.name) }));
}

/** Monday 00:00 of this week, local time: a status is "this week's" from then on. */
function weekStart(now = new Date()) {
    const d = new Date(now);
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return d;
}

/** A draft's sends; drafts sent before "sends" existed went to you. */
function sends(d) {
    if (d?.sends?.length) return d.sends;
    return d?.sentAt ? [{ target: 'me', to: d.sentTo ?? d.to, at: d.sentAt, body: d.sentBody }] : [];
}

/**
 * Where this week's status is: "sent" (to the team), "self" (only to you so far), "draft" (ready to send), or
 * "missing" (nothing drafted this week).
 */
function statusState(d, at, now = new Date()) {
    const monday = weekStart(now);
    const sentThisWeek = target => sends(d).some(x => x.target === target && new Date(x.at) >= monday);
    if (sentThisWeek('team')) return 'sent';
    if (sentThisWeek('me')) return 'self';
    if (d && !sends(d).length && at >= monday) return 'draft';
    return 'missing';
}

/** The week's status: read it, edit it, send it. Nothing goes out until you click Send and confirm in the desk. */
class WeeklyStatus extends LitElement {

    static properties = {
        _drafts: { state: true },
        _draft: { state: true },
        _picked: { state: true },
        _body: { state: true },
        _schedule: { state: true },
        _loading: { state: true },
        _sending: { state: true },
        _copied: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .draft { padding: 18px; display: grid; gap: 14px; min-width: 0; }
        .head { display: grid; gap: 4px; }
        .subject { font-weight: 600; font-size: 16px; overflow-wrap: anywhere; }
        .to { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        textarea, pre.sent {
            width: 100%; box-sizing: border-box; min-height: 26rem; resize: vertical; margin: 0;
            font: 13.5px/1.6 var(--font-code); color: var(--ink); background: var(--surface-2);
            border: 1px solid var(--line); border-radius: var(--r-control); padding: 14px 16px;
        }
        pre.sent { white-space: pre-wrap; overflow-wrap: anywhere; min-height: 0; }
        .acts { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
        .note { margin: 0; font-size: 13px; color: var(--muted); }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton.big { height: 50vh; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._loading = true;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        elf.values().then(v => { this._schedule = v.SCHEDULE; }).catch(() => {});
    }

    async _load() {
        this._loading = true;
        this._error = null;
        try {
            this._drafts = await drafts();
            const pick = this._drafts.find(d => d.id === this._picked) ?? this._drafts[0];
            const statuses = pick ? await elf.read('runs', this._drafts.map(d => d.file), ['sends', 'sentAt']) : {};
            this._drafts = this._drafts.map(d => ({ ...d, sends: sends(statuses[d.file]) }));
            this._draft = pick ? { ...(await elf.readJson('runs', pick.file)), id: pick.id, at: pick.at } : null;
            const d = this._draft;
            this._body = d ? ((await elf.store.get(`edit.${d.id}`)) ?? d.sentBody ?? d.body) : null;
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    _edit(e) {
        this._body = e.target.value;
        elf.store.set(`edit.${this._draft.id}`, this._body === this._draft.body ? null : this._body);
    }

    _discard() {
        elf.store.set(`edit.${this._draft.id}`, null);
        this._body = this._draft.body;
    }

    _sent(target) {
        return sends(this._draft).some(s => s.target === target);
    }

    /** Whether a send is still to come: the draft stays editable until it has gone to everyone (the team is enough). */
    _open(d) {
        if (this._sent('team')) return false;
        return !this._sent('me') || !!d.teamTo;
    }

    /** Send: the desk asks you first, in its own bar, then the elf emails it. */
    async _send(target) {
        this._sending = target;
        this._error = null;
        try {
            await elf.act('send', { draft: this._draft.id, body: this._body, target });
            await this._load();
            if (!this._open(this._draft)) elf.store.set(`edit.${this._draft.id}`, null);
        } catch (e) {
            if (!e.cancelled) this._error = e.message;
        } finally {
            this._sending = false;
        }
    }

    /** "I sent it myself" (or Undo): you emailed the team yourself, so this week is done. The elf sends nothing. */
    async _yourself(sent) {
        this._sending = 'yourself';
        this._error = null;
        try {
            await elf.act('sent-yourself', { draft: this._draft.id, sent });
            await this._load();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._sending = false;
        }
    }

    async _copy() {
        const d = this._draft;
        try {
            await copyText(`${d.subject}\n\n${this._open(d) ? this._body : d.sentBody ?? d.body}`);
            this._copied = true;
            setTimeout(() => { this._copied = false; }, 2000);
        } catch (e) {
            this._error = `Couldn't copy: ${e.message}`;
        }
    }

    _panel(d) {
        const open = this._open(d);
        const edited = open && this._body !== d.body;
        const address = target => target === 'team' ? d.teamTo : d.to;
        const sendButton = (target, label, cls) => this._sent(target) || (target === 'team' && !d.teamTo) ? ''
            : html`<button class=${cls} ?disabled=${!this._body?.trim() || !!this._sending} @click=${() => this._send(target)}>
                ${this._sending === target ? spinner : ''}${label} (${address(target)})</button>`;
        return html`
            <section class="panel draft" aria-label="The status">
                <div class="head">
                    <span class="subject">${d.subject}</span>
                    ${sends(d).length ? sends(d).map(x => x.yourself
                            ? html`<span class="to">You sent it to the team yourself (marked ${ago(x.at)})
                                <button class="sm quiet" ?disabled=${!!this._sending} @click=${() => this._yourself(false)}>Undo</button></span>`
                            : html`<span class="to">Sent ${ago(x.at)} to ${x.to}</span>`)
                        : html`<span class="to">Not sent yet</span>`}
                </div>
                ${open ? html`<textarea aria-label="Status text" .value=${this._body ?? ''} @input=${this._edit}
                        ?disabled=${!!this._sending}></textarea>`
                    : html`<pre class="sent">${d.sentBody ?? d.body}</pre>`}
                <div class="acts">
                    ${sendButton('team', 'Send to team', 'primary')}
                    ${sendButton('me', 'Send to me', '')}
                    <button @click=${this._copy}>${this._copied ? 'Copied' : 'Copy'}</button>
                    ${d.teamTo && !this._sent('team') ? html`<button class="quiet" ?disabled=${!!this._sending}
                        title="You emailed the team yourself: mark this week done. Sends nothing."
                        @click=${() => this._yourself(true)}>${this._sending === 'yourself' ? spinner : ''}I sent it myself</button>` : ''}
                    ${edited ? html`<button class="quiet" @click=${this._discard}>Discard my edits</button>` : ''}
                </div>
                ${open ? html`<p class="note">${edited ? 'Your edits are kept in this browser until you send. ' : ''}Sending
                    emails it from ${d.from || 'the elf\'s Gmail'} with this subject.
                    Send to me first if you want to check it; nothing goes out until you confirm.</p>` : ''}
            </section>`;
    }

    _runs() {
        return (this._drafts ?? []).map(d => {
            const s = d.sends ?? [];
            const label = s.some(x => x.target === 'team') ? 'sent to the team' : s.some(x => x.target === 'me') ? 'sent to you' : 'draft';
            return { id: d.id, label: `${runTime(d.at)} (${label})` };
        });
    }

    render() {
        const d = this._draft;
        const state = statusState(d, d?.at);
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Weekly status</h1>
                    <p class="sub">
                        ${d ? html`${d.weekStart} to ${d.weekEnd}, drafted ${runTime(d.at)}${this._picked ? ''
                            : state === 'sent' ? '. Sent to the team.' : state === 'self' ? '. Sent to you, not to the team yet.'
                            : state === 'draft' ? '. Ready to send.' : '. Nothing drafted this week yet.'}`
                            : this._loading ? '' : 'No status drafted yet.'}
                    </p>
                </div>${settingsButton(null, 'Weekly status settings')}</div>
                <elf-run-bar command="dry-run" label="Draft it now" .runs=${this._runs()} .picked=${this._picked}
                    note="Gathers your GitHub work of the last days and drafts the status with Claude. Sends nothing.${this._schedule
                        ? ` It also drafts itself ${calendarText(this._schedule)} and pops up a desktop reminder.` : ''}"
                    @run-picked=${e => { this._picked = e.detail; this._load(); }}
                    @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !d ? html`<div class="skeleton big"></div>` : ''}
                ${d ? this._panel(d) : !this._loading ? html`<p class="panel empty">Draft it now, or wait for its schedule.</p>` : ''}
            </div>
        `;
    }
}

/** The dashboard card: from Monday on, it asks for your attention until this week's is sent. */
class WeeklyStatusCard extends LitElement {

    static properties = { _draft: { state: true }, _state: { state: true }, _schedule: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
        elf.values().then(v => { this._schedule = v.SCHEDULE; }).catch(() => {});
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            const [newest] = await drafts();
            this._draft = newest ? { ...(await elf.read('runs', [newest.file], ['sends', 'sentAt', 'sentTo', 'to']))[newest.file], at: newest.at } : null;
            this._state = await elf.state();
        } catch (e) {
            this._draft = null;
        }
        this._loaded = true;
        elf.card({ attention: statusState(this._draft, this._draft?.at) !== 'sent' });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const d = this._draft;
        const state = statusState(d, d?.at);
        const say = state === 'sent' ? `This week's status went to the team ${ago(sends(d).find(x => x.target === 'team').at)}.`
            : state === 'self' ? html`<a href="#status">This week's status</a> went to you, not to the team yet.`
            : state === 'draft' ? html`<a href="#status">This week's status</a> is drafted and ready to send.`
            : html`Nothing drafted this week yet. <a href="#status">Draft it now</a>${this._schedule
                ? `, or it drafts itself ${calendarText(this._schedule)}` : ''}.`;
        const running = this._state?.running?.['dry-run'] ?? this._state?.running?.draft;
        return html`<p class="say">${say}</p>
            <div class="meta">${running ? html`<span class="running" role="status">${spinner}Drafting, started ${ago(running)}</span>` : ''}</div>`;
    }
}

customElements.define('weekly-status', WeeklyStatus);
customElements.define('weekly-status-card', WeeklyStatusCard);
document.body.append(document.createElement(elf.view === 'card' ? 'weekly-status-card' : 'weekly-status'));
