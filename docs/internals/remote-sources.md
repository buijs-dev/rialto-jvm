# Remote sources: internals

How rialto-jvm implements remote sources and the lock of a contract. What a tool must do, and why, is in the guide
[Remote sources and the lock](https://github.com/buijs-dev/rialto-spec/blob/develop/docs/guides/remote-sources.md) and
in [K4](https://github.com/buijs-dev/rialto-spec/blob/develop/spec/contract.md#k4-remote-sources); this document is
about the code. All classes are in `rialto-core`, package `dev.buijs.rialto.core.sources`, except `FileLocks` and
`httpFetch`, which know nothing of Rialto and are in `dev.buijs.rialto.core.utilities`.

## Overview

| Class | Role |
|---|---|
| `RemoteSources` (`RemoteSources.kt`) | The entry point: `update` (the command), `isRemote`, and `fetch`, the hook tests replace |
| `RemoteMode` (`RemoteSources.kt`) | `LOCKED` (a normal run), `OFFLINE`, `UPDATE`; each mode is a policy (see below) |
| `RemoteSession` (`RemoteSession.kt`) | One run: resolves each remote source, then pins the result in the lock and reports what failed |
| `RialtoLock` | Reads and writes the lock file: its name (`of`, `fileName`), its owner (`contract`), `read`, `update`, `text`, and `merge` with `rebase` for runs side by side |
| `FileLocks` (`core.utilities`) | Exclusive locks on a file, across threads and processes, with the lock files in a directory the caller names |
| `RialtoCache` | The cache directory: documents by hash under `sha256/` with the name `nameOf(url)`, file locks under `locks/` |
| `httpFetch` (`core.utilities`) | The default of `RemoteSources.fetch`: a GET over HTTP, 200 or an exception |
| `SourceChange` | One changed pin, as `rialto sources update` and `rialtoSourcesUpdate` report it |

### Modes

`RemoteMode` answers the four questions a run asks, so the code checks a property instead of comparing modes:

| Mode | `readsCache` | `fetches` | `enforcesPins` | `writesLock` |
|---|---|---|---|---|
| `LOCKED` | yes | yes | yes | yes |
| `OFFLINE` | yes | no | yes | no |
| `UPDATE` | no | yes | no | yes |

- `readsCache`: a pinned source is read from the cache when it is there.
- `fetches`: a source not read from the cache is fetched; otherwise it is `S003`.
- `enforcesPins`: a fetched source whose hash differs from its pin is `S004`.
- `writesLock`: `finish` writes the lock at the end of a run without errors.

### One run

A run goes through one `RemoteSession`:

```text
ContractValidator.validate / RemoteSources.update
  └─ RemoteSession(contractFile, mode, diagnostics)
       ├─ readLock()                        once: LockState.Readable(pins) or LockState.Unreadable(reason), never empty
       ├─ resolve(url, location)            once per remote source
       │    ├─ not https                    → rejectScheme: S005
       │    ├─ LockState.Unreadable         → remember as unpinned, no fetch
       │    ├─ fromCache                    → pinned, mode.readsCache and cached: the cached copy
       │    └─ download
       │         ├─ !mode.fetches           → rejectOffline: S003
       │         ├─ fetchBody fails         → S003
       │         ├─ hash ≠ pin              → rejectChanged: S004 (only when mode.enforcesPins)
       │         └─ otherwise               → store in the cache
       │    (every source read or downloaded is added to `accepted`: url → hash)
       └─ finish()                          once, at the end of the run
            ├─ LockState.Unreadable         → reportUnreadableLock: K010, naming the unpinned sources
            ├─ any error in the run         → write nothing
            ├─ changesSince(pins)           → the SourceChanges to return
            ├─ !mode.writesLock, or accepted = pins → write nothing
            └─ writeLock(base = pins, next = accepted) → RialtoLock.merge, which is update { rebase(...) }
                 ├─ WriteResult.Written     → the changes
                 ├─ WriteResult.Conflict    → reportConflict: K010
                 └─ WriteResult.Failed      → no changes: ignored; otherwise reportWriteFailure: K010
```

`update` (the command) reads the contract, takes its remote sources with `remoteSourcesOf`, resolves each in an
`UPDATE` session and returns what `finish` gives.

The new state is exactly the sources of this run: an entry the contract no longer uses is dropped. That is only
correct because every contract has its own lock (K4.6); `RialtoLock` refuses a lock that names another contract
(K4.7), so a run never drops the pins of another contract.

`RemoteSession` reports into the `Diagnostics` of the step that created it, so its findings appear between the others in
the order of the sources, and `finish` sees every error of the run, not only those of remote sources.

## The lock file

- `RialtoLock.of(root)` gives the lock of a contract: `RialtoLock.fileName` removes a trailing `.yaml`, `.yml` or
  `.json`, then `.rialto`, and adds `.rialto.lock`. The path is next to the root as given (`toAbsolutePath`), not
  its real path.
- `read()` throws `RialtoLock.Unreadable` for every reason the lock is no lock: a directory, an IO error (message from
  `osMessage`, without the path), invalid YAML, not a mapping, `sources` not a mapping, a hash that is not a
  lowercase SHA-256, entries without `contract`, or a `contract` that is not this root's file name. A missing file, an
  empty document, `~` and a mapping without `sources` are an empty lock.
- `text(contract, content)` writes the header comment, `contract`, then `sources` sorted by URL, with both strings
  quoted as JSON strings. The same content always gives the same bytes, so a lock never changes without a change of
  content.
- Other top-level keys are ignored on read, and not written back.

## Concurrency and atomicity

Builds on one checkout run side by side: a Gradle daemon and the command line, parallel Gradle tasks, or two plugin
class loaders in one JVM. `RialtoLock.update(change)` makes every change of the lock one step:

1. **Take the file lock** (`FileLocks.exclusive(file, RialtoCache.locks())`). First a `ReentrantLock` per real path, so threads of this class
   loader take turns per file; then a `FileChannel.tryLock` on `<cache>/locks/<sha256 of the real path>.lck`, retried
   every 20 ms, for other processes and other class loaders. The wait is bounded by `FileLocks.timeout` (60 s), after
   which it throws `FileLocks.Timeout`.
2. **Read the lock again** under the file lock, so the change never starts from a stale copy.
3. **Apply the change** and write only when the result differs.
4. **Write atomically** (`writeAtomically`): a temporary file and a rename, so a reader never sees half a file. Where
   the file system has no atomic rename, it falls back to a plain replace.

Why the file lock is not the lock file itself: the rename replaces the file, so a lock held on the old file no longer
protects the new one. Why it lies in the cache and not next to the lock: no `.lck` file appears in a source tree. The
key is the real path (links of the file and of its directory resolved), so every path to the same lock takes the same
file lock. Without a writable cache only threads in this process take turns, and the atomic rename is the only
protection between processes.

A change nested in a change of the same file in the same thread is refused with `IllegalStateException`: the outer
change would overwrite the inner one.

### Rebase

`RemoteSession` read the lock at the start of the run (`base`, the pins of `LockState.Readable`); another run may have
written it since (`theirs`). `RialtoLock.merge(base, next)` applies this run's change (`ours`, the `accepted` hashes)
onto `theirs` inside `RialtoLock.update`, with `RialtoLock.rebase(base, ours, theirs)`, a three-way merge per URL,
and returns `Merge.Merged` or `Merge.Conflict`. `writeLock` in the session also catches the `IOException` of a failed
write, and returns a `WriteResult` (`Written`, `Conflict` or `Failed`), which `finish` turns into the result of the
run:

| This run | The other run | Result |
|---|---|---|
| unchanged | anything | theirs |
| changed | unchanged | ours |
| changed | changed to the same hash | that hash |
| dropped | changed | theirs: their contract still uses it |
| pinned | pinned to another hash | conflict: nothing is written, `K010` with "Run again" |

With one lock per contract, the other run is a run on the same contract, such as Gradle and the command line at the
same time.

## Failure handling

| Failure | Result |
|---|---|
| The lock cannot be read | `K010` from `finish`, with the sources left unpinned; the sources are not fetched; the lock is left as it is |
| Any error in the run | Nothing is written: a failed run never pins a state that was only partly checked |
| The write fails and the run only drops unused entries | No error; the next run drops them |
| The write fails with a new or changed hash | `K010` "Cannot write the lock", hint "Repair or remove …" |
| `FileLocks.Timeout` | `K010` with the hint "Wait for the other run to finish, or stop it, then run again" |
| A conflict in `rebase` | `K010` "Another run changed the lock …", hint "Run again …" |

`K010` is always reported on the contract (`Location(contractFile)`), not on a source, because the lock belongs to the
contract as a whole.

## Tests

| Test class | What it covers |
|---|---|
| `RialtoLockTest` | The name rule, the owner check, reading every kind of unreadable lock, writing only a change, the exact bytes, atomic replace and its fallback, file locks across threads and holders, timeouts, links and loops of links, `rebase`, and `merge` with and without a conflict |
| `RemoteSourcesTest` | The three modes, S003/S004/S005, dropping unused entries, unreadable and unwritable locks, two contracts in one directory, two roots with one lock name, concurrent runs and conflicts, the cache directory |
| `RialtoCacheTest` | The cache directory, storing and finding a document by hash, and `nameOf` keeping names inside their directory |
| `HttpFetchTest` | `httpFetch` against a server on the loopback interface: a 200, a redirect, another status |
| `IncludeTest`, `RialtoPluginTest` | The lock as an input of a build (`ContractFiles.inputs`) |
| `CommandsTest` | `rialto sources update` and `--offline` on the command line |
| `ContractValidatorTest` | The test contracts of the specification, including `invalid/lock-directory/` and `invalid/lock-other-contract/` |
