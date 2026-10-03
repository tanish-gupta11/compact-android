package com.compact.work;

public enum JobState {
  PENDING,
  ANALYSED,
  ENCODING,
  ENCODED,
  VERIFIED,
  PUBLISHING,
  PUBLISHED,
  ORIGINAL_TRASHED,
  DONE,
  SKIPPED,
  FAILED,
  RESTORED;

  public JobState recovered() {
    switch (this) {
      case ANALYSED:
      case ENCODING:
      case ENCODED:
      case VERIFIED:
        return PENDING;
      default:
        return this;
    }
  }

  public boolean terminal() {
    return this == DONE || this == SKIPPED || this == FAILED || this == RESTORED;
  }

  public boolean canTrash() {
    return this == PUBLISHED;
  }

  public boolean allows(JobState next) {
    if (next == this) return true;
    if (next == SKIPPED || next == FAILED) return !terminal() && this != ORIGINAL_TRASHED;
    if (next == PENDING)
      return this == ANALYSED
          || this == ENCODING
          || this == ENCODED
          || this == VERIFIED
          || this == PUBLISHING;
    switch (this) {
      case PENDING:
        return next == ANALYSED;
      case ANALYSED:
        return next == ENCODING;
      case ENCODING:
        return next == ENCODED;
      case ENCODED:
        return next == VERIFIED;
      case VERIFIED:
        return next == PUBLISHING;
      case PUBLISHING:
        return next == PUBLISHED || next == DONE;
      case PUBLISHED:
        return next == ORIGINAL_TRASHED || next == DONE;
      case ORIGINAL_TRASHED:
        return next == RESTORED || next == DONE;
      default:
        return false;
    }
  }
}
