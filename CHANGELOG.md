# Changelog

All notable changes to this project will be documented in this file.

---

## [1.0.5] — 2026-04-05

### Features
- Migrated codebase to fully support **Projection Specification 3.0.0** (Exposure Layer & Composed Criteria).
- Implemented full support for the `@ExposedAs` annotation on scalar properties, with strict `SCREAMING_SNAKE_CASE` enforcement.
- Integrated the `@Exposure` annotation at class-level to define root namespace and strategy configurations cleanly.
- Implemented recursive constraint checking (DFS) for inheriting composed criteria across sub-projections using `@Projected(as = "PREFIX", cycleBreak = true)`.
- Introduced compile-time collision checks for duplicate criteria prefixes and cyclic composition dependencies (unless `cycleBreak` is utilized).
- Generates updated 6-parameter `ProjectionMetadata` configurations integrating `ExposedCriterion` and `ExposureMetadata`.

### Refactoring
- Deprecated and removed the legacy `reducers = {"SUM"}` property parsing in favor of the new inline v3.0.0 reducer syntax (`"path:REDUCER"`).

## [1.1.0] — since v1.0.3

### Features
- Added support for allowing a computing method of a computed field to have parameter mismatches if compatible 
(autoboxing/type promotion context).
- Added support for autoboxing/unboxing as well as type promotion when resolving compute method signatures.

### Documentation
- Enhanced Javadoc across the API.

### Refactoring
- Switched to a new paradigm using only `@Projection` on interfaces.

---

## [v1.0.3] — 2026-02-10

### Features
- Added a new method utility to look up method signatures at compile time.

### Fixes
- Fixed `@Method` annotation to use the `value` attribute for the method name instead of `method`.

---

## [v1.0.2] — 2026-02-07

### Refactoring
- Separated API definition from the annotation processor, which is now defined solely by the current project.

---

## [v1.0.1] — 2026-02-07

### Fixes
- Removed the need for `@AutoService` on generated provider implementation classes.
- Added `use` statements for expected generated services within `module-info.java`.

---

## [v1.0.0] — 2026-02-07

Initial release.
