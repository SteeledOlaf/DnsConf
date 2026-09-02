package com.novibe.common.util;

import com.novibe.common.base_structures.HostsLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataParserTest {

    @Test
    void parsesHostsEntryAndInlineComment() {
        HostsLine line = DataParser.parseHostsLine("0.0.0.0 www.example.com # comment");
        assertNotNull(line);
        assertEquals("0.0.0.0", line.ip());
        assertEquals("example.com", line.domain());
    }

    @Test
    void rejectsThreeColumnEntry() {
        assertNull(DataParser.parseHostsLine("0.0.0.0 one.example two.example"));
    }
}
