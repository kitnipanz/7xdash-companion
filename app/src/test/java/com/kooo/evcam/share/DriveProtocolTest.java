package com.kooo.evcam.share;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DriveProtocolTest {

    @Test
    public void deviceCodeKeepsTheCompleteUrlForTheQr() {
        DriveProtocol.DeviceCode code = DriveProtocol.parseDeviceCode(
                "{\"device_code\":\"dev\",\"user_code\":\"ABCD-EFGH\","
                        + "\"verification_url\":\"https://www.google.com/device\","
                        + "\"verification_uri_complete\":\"https://www.google.com/device?user_code=ABCD-EFGH\","
                        + "\"interval\":5,\"expires_in\":1800}");
        assertEquals("ABCD-EFGH", code.userCode);
        assertEquals("https://www.google.com/device?user_code=ABCD-EFGH", code.qrUrl);
        assertEquals(5, code.intervalSec);
    }

    @Test
    public void missingDeviceCodeIsNotALogin() {
        assertNull(DriveProtocol.parseDeviceCode("{\"error\":\"invalid_client\"}"));
    }

    @Test
    public void pollWaitsUntilThePhoneApproves() {
        assertEquals(DriveProtocol.Poll.PENDING,
                DriveProtocol.classifyPoll("{\"error\":\"authorization_pending\"}"));
        assertEquals(DriveProtocol.Poll.SLOW_DOWN,
                DriveProtocol.classifyPoll("{\"error\":\"slow_down\"}"));
        assertEquals(DriveProtocol.Poll.DENIED,
                DriveProtocol.classifyPoll("{\"error\":\"access_denied\"}"));
        assertEquals(DriveProtocol.Poll.GRANTED,
                DriveProtocol.classifyPoll("{\"access_token\":\"ya29\",\"refresh_token\":\"1//\"}"));
    }

    @Test
    public void refreshKeepsTheOldRefreshToken() {
        DriveProtocol.Tokens tokens = DriveProtocol.parseTokens(
                "{\"access_token\":\"ya29\",\"expires_in\":3600}", "1//old", 1_000_000L);
        assertEquals("ya29", tokens.accessToken);
        assertEquals("1//old", tokens.refreshToken);
        assertFalse(DriveProtocol.accessFresh("ya29", tokens.expiresAtMs, tokens.expiresAtMs));
        assertTrue(DriveProtocol.accessFresh("ya29", tokens.expiresAtMs, tokens.expiresAtMs - 1));
    }

    @Test
    public void metadataQuotesTheFileName() {
        assertEquals("{\"name\":\"a\\\"b.mp4\",\"parents\":[\"folder\"]}",
                DriveProtocol.fileMetadata("a\"b.mp4", "folder"));
        assertEquals("video/mp4", DriveProtocol.mimeFor("20250101_120000_surround.mp4"));
        assertEquals("image/jpeg", DriveProtocol.mimeFor("shot.JPG"));
    }
}
