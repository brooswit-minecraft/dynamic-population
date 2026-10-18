bump: minor

### Added
- Emerald promotion economy: giving a Fillager the live-configured number of emeralds (default 1, `fillager_promotion_emerald_cost`, 1..64) converts it to a normal, permanent villager and consumes the emeralds. Promotion never writes any cell's target density; the felt population cost comes entirely from the existing bounded refill rate.
