package com.headswap.desk.dex;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HudActivationPreferenceTest {
    private static boolean enabled(String xml) {
        return HudActivationPreference.enabled(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void minifiedMapReadsEitherValueRegardlessOfAttributeOrder() {
        assertTrue(enabled("<map><int name='fps' value='30'/><boolean name=\"metaNavi\" value=\"true\" /></map>"));
        assertFalse(enabled("<map><boolean value='false' name='metaNavi'/><string name='other'>true</string></map>"));
        assertTrue(enabled("<map>\n<boolean\n value=\"true\"\n name=\"metaNavi\" />\n</map>"));
    }

    @Test public void missingKeyUsesTheAppDefaultButMalformedOrAmbiguousInputFailsClosed() {
        assertTrue(enabled("<map/>"));
        assertTrue(enabled("<map><set name='other'><string>metaNavi=false</string></set></map>"));
        assertFalse(enabled(""));
        assertFalse(enabled("<map><boolean name='metaNavi' value='true'/></ma>"));
        assertFalse(enabled("<list/>"));
        assertFalse(enabled("<map><string name='metaNavi'>true</string></map>"));
        assertFalse(enabled("<map><boolean name='metaNavi' value='maybe'/></map>"));
        assertFalse(enabled("<map><boolean name='metaNavi' value='true'/><boolean name='metaNavi' value='false'/></map>"));
    }

    @Test public void rejectsDoctypesAndOversizedFiles() {
        assertFalse(enabled("<!DOCTYPE map [<!ENTITY x 'true'>]><map><boolean name='metaNavi' value='&x;'/></map>"));
        assertFalse(enabled("<map>" + " ".repeat(HudActivationPreference.MAX_BYTES) + "</map>"));
    }
}
