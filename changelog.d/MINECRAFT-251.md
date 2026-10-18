bump: minor

### Added
- Lightweight Fillagers and bounded, live-configurable sampling-window reconciliation.

### Fixed
- Register Fillager attributes and client nitwit rendering; keep safe spawn positions inside the reconciled vertical cell.
- Stop the sampling window from undercounting density near the top/bottom of the world (it was averaging in nonexistent layers as zero).
- Recognize cactus, campfire, drowning, and suffocation as unsafe-location deaths (AC9), not only lava/fire/soul-fire.
