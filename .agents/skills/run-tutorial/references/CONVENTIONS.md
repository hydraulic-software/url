# Conventions for writing runscripts

## Rules

The following are mandatory requirements of `run.zip` scripts:

1. Respect `VER` if it's set. It contains a string with the user's requested version of the app.
2. Don't do any user interaction.

## Suggestions

The following are guidelines designed to make your users happy. Ignore them at your peril!

1. Don't use `curl` to download files, use `url` to benefit from its features like automatic cleanup of the cache.
2. Don't install files or do OS integration (see below for how to do that).
3. On macOS, don't override Gatekeeper with `--no-gatekeeper` or ship unsigned binaries. Although you _can_ do that, users who have chosen to use macOS expect apps to follow its security conventions. If you _really_ are so impoverished you can't afford an Apple Developer account, ask a user who has one to do it for you.
4. 
