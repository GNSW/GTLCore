package org.gtlcore.gtlcore.integration.ae2.storage;

import appeng.api.stacks.AEKey;

import java.math.BigInteger;

/** Exact physical quantity. Planning callers must separately enforce extraction permissions and reservations. */
public interface PreciseStorageAmount {

    BigInteger getExactStoredAmount(AEKey key);
}
