# UnsignedChat

Lets Velocity and BungeeCord plugins cancel or edit chat and commands without kicking 1.19.3+ players.

I made this because `ChatResult.denied()` on Velocity kicks players with "A plugin tried to cancel a signed chat message". UnsignedChat strips the chat signatures on the proxy, so your plugins can deny or change messages normally.

- One jar for Velocity and BungeeCord
- Proxy only, nothing to install on backends
- Backends from 1.8 to 1.21.x
- Tiny, no dependencies

## Install
Drop the jar into your proxy's `plugins` folder and restart.

## Notes
- Backends on 1.19.3+ need `enforce-secure-profile=false` in `server.properties`.
- Don't use it together with SignedVelocity.
- 1.19 to 1.19.2 clients aren't covered.
- Players can't report chat sent through the proxy, since the signatures are removed.

## Build
`mvn package` (Java 17+)

## License
MIT
