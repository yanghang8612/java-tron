package org.tron.common.utils;

/** Storage format shared by the statistics node and its offline migration. */
public final class FastSyncStorage {

  public static final long HEADER_RETENTION_BLOCKS = 65_536L;
  public static final String GENESIS_ID_KEY = "HEADER_ONLY_GENESIS_ID";
  public static final String MIGRATION_PENDING_FILE = "header-only-migration.pending";

  private FastSyncStorage() {
  }
}
