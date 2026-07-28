# ADR-0002: Use libxposed remote preferences for module configuration

## Status

Accepted — 2026-07-27

## Context

The previous exported `ContentProvider` configuration bridge was blocked by package-visibility and ROM-level application hiding in the Apple Music process. libxposed API 102 provides framework-owned remote preferences intended for module configuration.

## Decision

Target libxposed API 102. The module application binds `libxposed/service`, verifies API 102 and `PROP_CAP_REMOTE`, then writes the `settings` preference group. The hooked process obtains the same group through `XposedModule.getRemotePreferences()` as read-only preferences. Existing private settings are migrated once when the service first binds.

The settings UI remains read-only until the remote service is available. Missing liquid-glass keys default to disabled. Target-side health is written to the Xposed log because hooked processes cannot edit remote preferences.

## Consequences

- Configuration no longer depends on Android package visibility or an exported module component.
- This module version requires a framework implementing API 102; API 100-era LSPosed releases cannot load it.
- Resource hooks removed by API 102 are replaced by a narrowly scoped `LayoutInflater.inflate(int, ViewGroup, boolean)` interceptor and layout-name dispatch.
- The old caller-verified Provider is removed from the manifest and production code.
