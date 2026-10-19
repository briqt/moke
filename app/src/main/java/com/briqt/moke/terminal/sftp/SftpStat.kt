package com.briqt.moke.terminal.sftp

import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode

/** lookup is sshj statExistence: only its real NO_SUCH_FILE result is null; all failures propagate. */
internal fun readSftpStat(path: String, lookup: (String) -> FileAttributes?): RemoteStat? =
    lookup(path)?.let { RemoteStat(it.size, it.mtime, it.mode.type == FileMode.Type.DIRECTORY) }
