# Terminal Feature Feasibility (andClaw)

## Short answer
Haan, is repo me **real working terminal** add karna possible hai jahan user commands run kar sake.

## Current repo findings
- App already proot Ubuntu environment me commands run kar sakti hai via `ProotManager` (`executeWithResult`, `executeWithStreamingOutput`, `executeWithStreamingText`).
- Gateway process lifecycle already `ProcessManager` aur `GatewayService` handle karte hain.
- Dashboard me logs ka live UI already present hai, isliye streaming output ko UI tak lane ka pattern existing hai.

## What is missing for a true interactive terminal
A real terminal ke liye sirf line-by-line command execution enough nahi hota. Neeche capabilities add karni hongi:
1. **PTY (pseudo-terminal) session** per user terminal.
2. **Bidirectional stream** (stdin + stdout/stderr) in near real-time.
3. **Resize support** (rows/cols) taaki `vim`, `top`, `htop` jaise TUIs work karein.
4. **Session lifecycle controls** (start, stop, reconnect, timeout).
5. **Safety controls** (allowlist/confirmations, kill switch, storage limits).

## Recommended architecture
1. New `TerminalSessionManager` (Kotlin) in `proot/`:
   - proot + `/bin/bash -l` ko PTY ke saath spawn kare.
   - Session ID maintain kare.
   - Read/write channels expose kare.
2. New ViewModel state for terminal screen:
   - terminal buffer + connection state + prompt state.
3. New Compose screen:
   - monospace text viewport,
   - input bar,
   - special keys row (Ctrl, Esc, Tab, arrows).
4. Optional hardening:
   - profile mode (`readonly`, `developer`, `full shell`),
   - command audit log.

## Practical constraints on Android
- Android par native PTY handling ke liye ya to JNI bridge chahiye ya robust shell/pty wrapper strategy.
- Long-running interactive sessions battery + foreground service policy ke saath align hone chahiye.
- App sandbox + proot bind mounts carefully set karne honge so that terminal expected files dekh sake.

## MVP scope (recommended)
Phase 1 (1-2 sprints):
- Single session shell (`bash`) with streaming I/O,
- basic history,
- clear + kill session buttons,
- no multi-tab support.

Phase 2:
- multi-session tabs,
- terminal resize,
- paste handling,
- reconnect on app resume.

## Conclusion
Repo ki existing architecture dekh kar clear hai ki foundation ready hai. 
**Interactive real terminal feasible hai**, aur sabse bada gap PTY/session layer ka hai, na ki UI ya process-exec ka.
