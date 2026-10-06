# PVCPipe

PVCPipe lets users watch remote streams and keep local viewing data and settings.

## Backup and restore

**Backup archive**:
A saved copy of PVCPipe's local database that may also contain application settings.

**Backup inspection**:
An assessment of a selected backup archive that informs the user's restore choices.
Inspection does not establish that the archive can be restored successfully.

**Restore**:
Replacement of PVCPipe's local database from a backup archive, optionally including
application settings.

**Accepted restore**:
A restore request admitted by the application for completion independently of the
settings screen's lifetime.

**Pending database restore**:
A replacement database prepared for activation when the application next starts.

## Downloads

**Download selection**:
The media and options a user chooses for a local download.

**Download destination**:
The folder or document chosen to hold a downloaded file.

**Download preparation**:
Determining how a download selection will become a local file and resolving
whether its destination can be used.

**Download collision**:
A destination already used by an existing file or download.

**Submitted download**:
A prepared download handed off for background execution.
Submission does not establish that the download has started or completed.
