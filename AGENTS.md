# AGENTS.md

## Project organization

- Code is in `/src/`
- Tests are in `/fixtures/`

## Build and test

- Always spotless check when making changes:
  - `mvn -N -q spotless:check`
- Tests can be run with:
  - `mvn test`

## Do

- Verify format with spotless check. If you get a spotless error run `mvn spotless:apply`.
- Verify that tests pass.
- When adding new logic/syntax test behavior in `.fixture` files.
- When creating new `.fixture` files only have one empty line between the source code and the token header.
- Import Java classes. Don't use the full package path in the code implementation.
