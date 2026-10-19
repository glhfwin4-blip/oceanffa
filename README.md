# OceanFFA — Paper 1.21.11

Buildable Java source for Paper 1.21.11 / Java 21.

## Player commands
`/spawn`, `/kits`, `/stats`, `/stats <player>`, `/kills`, `/balance`, `/shop`, `/daily`, `/rewards`, `/ping`, `/settings`.

## Added FFA features
- Each player kill awards 1 token and 1 point by default.
- Custom configurable `/shop` purchases use tokens.
- Blocks can only be placed inside a configured PvP cuboid and not within the spawn protection radius.
- Allowed blocks decay randomly after 20–30 seconds by default.
- Floating top kills, top points, and top deaths boards refresh every 60 seconds.
- Admin: `/oceanadmin pvp1`, `/oceanadmin pvp2`, `/oceanadmin leaderboard`, `/oceanadmin give <player> <amount>`, `/oceanadmin set <player> <amount>`, `/oceanadmin reload`.

## Setup
1. Build with Java 21 and Maven 3.9+: `mvn clean package`.
2. Upload `target/OceanFFA-1.1.0.jar` to `plugins/` and restart Paper.
3. Set spawn coordinates in `plugins/OceanFFA/config.yml` or leave `spawn.world` blank to use the default world spawn.
4. Stand at one PvP-region corner and run `/oceanadmin pvp1`; stand at the opposite corner and run `/oceanadmin pvp2`.
5. Stand at the desired board location (usually spawn) and run `/oceanadmin leaderboard`.

If the PvP region is unset, block placement is denied everywhere. Spawn protection is a horizontal radius around spawn. `/stats <player>` requires the target online. `/settings` saves preference toggles but does not automatically control other plugins. Source only; compile and test on a staging server before production.
