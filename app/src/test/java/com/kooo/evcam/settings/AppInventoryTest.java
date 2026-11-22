package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PermissionInfo;

import org.junit.Test;

public class AppInventoryTest {

    @Test
    public void grantedFollowsTheRequestedFlag() {
        assertTrue(AppInventory.granted(AppInventory.REQUESTED_PERMISSION_GRANTED));
        assertFalse(AppInventory.granted(0));
    }

    @Test
    public void protectionNamesTheBaseAndTheFlags() {
        assertEquals("normal", AppInventory.protection(PermissionInfo.PROTECTION_NORMAL, 0));
        assertEquals("dangerous", AppInventory.protection(PermissionInfo.PROTECTION_DANGEROUS, 0));
        assertEquals("signature|privileged", AppInventory.protection(
                PermissionInfo.PROTECTION_SIGNATURE, PermissionInfo.PROTECTION_FLAG_PRIVILEGED));
    }
}
