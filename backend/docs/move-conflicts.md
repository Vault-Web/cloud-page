# Move and rename conflicts

File and folder moves reject an occupied destination with HTTP 409 and a plain-text
`Destination already exists: <relative path>` response. Both entries are retained;
the operation does not move either entry to Trash. Successful moves and normalized
same-path no-ops keep their existing behavior. Shared moves use the same services.

The implementation uses Java's non-replacing `Files.move`, without `ATOMIC_MOVE`
or `REPLACE_EXISTING`. Atomic moves have provider-specific destination replacement
semantics and must not be substituted for this conflict policy. This is not a
cross-process filesystem transaction: external writers and filesystem-provider
race behavior remain outside the guarantee. No locking protocol for independent
writers is introduced.

## Compatibility and rollout

- No database, file-layout, environment, authentication or successful-response
  migration is needed. Previously destructive successful requests now fail with
  the existing HTTP 409 conflict contract.
- Clients must report the failure and retain unsaved editor content. The portal's
  existing rename-before-save regression covers a 409 response without uploading
  or closing the editor; its move flow reports individual failures.
- Review and deploy this service version using the normal operator-controlled
  process. No deployment pointers are changed by this fix.
- Code rollback requires no data conversion, but restores destructive replacement
  behavior. It cannot recover data overwritten by older versions. Keep normal
  filesystem backups and restore lost data from backups when necessary.

## Validation

From `backend/` on Linux with Java 21:

```bash
./mvnw -B spotless:check verify
```

Filesystem regressions exercise file/folder and empty-folder collisions,
normalized paths, dangling destination symlinks and same-path operations.
Controller regressions execute the real move methods against temporary files and
assert HTTP 409 with both source and destination contents intact.
