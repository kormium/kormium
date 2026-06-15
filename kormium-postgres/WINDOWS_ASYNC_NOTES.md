# Windows true-async reactor — notes

Linux/macOS and Windows all have a true-async libpq path: a poll-based socket reactor
(`SocketReactorBase` + `UnixSocketReactor` on Unix, `WindowsSocketReactor` on mingwX64).
`createSocketReactor()` returns a real reactor on every native target, so the driver never
needs the blocking offload for its suspend path.

## The Windows cinterop problem (resolved)

Kotlin/Native's `platform.windows` does **not** expose `WSAPoll`/`WSADATA`/`SOCKET`/etc, and a
direct cinterop on the system `<winsock2.h>` produces an **empty binding package**: cinterop
runs without error yet the compiler resolves none of `SOCKET`, `socket`, `WSAPoll`,
`sockaddr_in`, ... (confirmed identical on macOS/Linux cross-compile and a native Windows host).
`headerFilter` variants, `-D_WIN32_WINNT`, `-DWIN32_LEAN_AND_MEAN`, and explicit sysroot `-I`
all left the package empty. `klib dump-abi`/`contents` report 0 even for the working libpq
cinterop, so only the compiler is ground truth.

## Solution: project-owned C shim

`src/nativeInterop/cinterop/winsock_shim.h` `#include`s `<winsock2.h>`/`<ws2tcpip.h>` and wraps
the calls the reactor needs behind a small `ksock_*` API of `static inline` functions. cinterop
emits declarations from a **project-owned** header (matched by `headerFilter`), unlike a system
header, and compiles the inline bodies into the generated stub klib — so no separate `.c` or
extra link step beyond `-lws2_32`. The whole Winsock surface (`SOCKET` handles, `sockaddr_in`,
`WSAPOLLFD`, the `POLL*` flags) stays in C; Kotlin sees only opaque 64-bit handles (`ksock_t`)
and a flat `ksock_pollfd`.

The wake channel is a loopback UDP socket `connect()`ed to its own bound address, so signalling
is a plain `send()` and draining a `recv()` loop (Winsock cannot poll a pipe). `WindowsSocketReactor`
drives that API; `winsock.def`/`build.gradle.kts` register the cinterop with the cinterop dir on
the include path.

Verified on a native Windows host: `cinteropWinsockMingwX64`, `compileKotlinMingwX64`, and
`linkDebugTestMingwX64` all succeed (`ws2_32` + libpq link clean).
