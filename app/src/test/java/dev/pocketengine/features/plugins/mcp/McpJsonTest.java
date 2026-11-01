package dev.pocketengine.features.plugins.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class McpJsonTest {
    @Test public void shortExponentCannotExpandPastTheCanonicalNumberBudget()throws Exception {
        String source="{\"n\":1e100000}";
        assertEquals(14,source.length());
        try {
            McpJson.digest(McpJson.parse(source));
            fail("Expected oversized canonical number rejection");
        } catch(IllegalArgumentException expected) { }
    }

    @Test public void ordinaryEquivalentNumbersKeepTheExistingCanonicalBytes()throws Exception {
        for(String number:new String[]{"1","1.0","1e0","10e-1","0.100e1"})assertNumberDigest(number,"1");
        for(String number:new String[]{"0","-0","-0.000","0e100000","-0e-100000"})assertNumberDigest(number,"0");
        assertNumberDigest("123.45000","123.45");
        assertNumberDigest("-12.300e2","-1230");
        assertNumberDigest("1.25e-3","0.00125");
        assertNumberDigest("9007199254740993","9007199254740993");
    }

    @Test public void nestedCanonicalDigestKeepsItsExistingIdentity()throws Exception {
        assertEquals("d964212e271d33780323239e11bab58256b01fafb5049d2aa207ceb4624670db",
                McpJson.digest(McpJson.parse("{\"z\":1.2300,\"n\":1e2,\"a\":[-0.00,true,null,\"text\"]}")));
    }

    @Test public void extremeExponentsAndScalesAreRejectedWithoutPlainExpansion()throws Exception {
        for(String number:new String[]{"-1e100000","1e-100000","-1e-100000",
                "1e2147483647","-1e2147483647","1e-2147483647","-1e-2147483647","1000e2147483647"}) {
            assertNumberRejected(number);
        }
        assertRejected(new BigDecimal(BigInteger.ONE,Integer.MAX_VALUE));
        assertRejected(new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE));
    }

    @Test public void canonicalNumberBudgetCountsSignAndDecimalPoint()throws Exception {
        assertNumberDigest("1e4095","1"+"0".repeat(4095));
        assertNumberDigest("-1e4094","-1"+"0".repeat(4094));
        assertNumberDigest("1e-4094","0."+"0".repeat(4093)+"1");
        assertNumberDigest("-1e-4093","-0."+"0".repeat(4092)+"1");
        for(String number:new String[]{"1e4096","-1e4095","1e-4095","-1e-4094"})assertNumberRejected(number);
    }

    @Test public void precisionIsBoundedAfterInsignificantZerosAreRemoved()throws Exception {
        String integer="9".repeat(4096),fraction="9".repeat(4094)+".1";
        assertEquals(McpJson.sha256(integer),McpJson.digest(new BigDecimal(integer)));
        assertEquals(McpJson.sha256(fraction),McpJson.digest(new BigDecimal(fraction)));
        assertRejected(new BigDecimal("9".repeat(4097)));
        assertRejected(new BigDecimal("9".repeat(4095)+".1"));
        assertEquals(McpJson.sha256("0.1"),McpJson.digest(new BigDecimal("0.1"+"0".repeat(5000))));
    }

    @Test public void zeroWithSupportedExtremeScalesKeepsTheExistingIdentity()throws Exception {
        assertNumberDigest("0e2147483647","0");
        assertNumberDigest("-0e-2147483647","0");
        assertEquals(McpJson.sha256("0"),McpJson.digest(new BigDecimal(BigInteger.ZERO,Integer.MIN_VALUE+1)));
        assertEquals(McpJson.sha256("0"),McpJson.digest(new BigDecimal(BigInteger.ZERO,Integer.MAX_VALUE)));
    }

    @Test public void ordinaryJavaNumberTypesKeepTheExistingIdentity()throws Exception {
        assertEquals(McpJson.sha256("1"),McpJson.digest(Integer.valueOf(1)));
        assertEquals(McpJson.sha256("1"),McpJson.digest(Long.valueOf(1)));
        assertEquals(McpJson.sha256("1.25"),McpJson.digest(Float.valueOf(1.25f)));
        assertEquals(McpJson.sha256("1.25"),McpJson.digest(Double.valueOf(1.25)));
        assertEquals(McpJson.sha256("9007199254740993"),McpJson.digest(new BigInteger("9007199254740993")));
    }

    private static void assertNumberDigest(String source,String canonical)throws Exception {
        assertEquals(source,McpJson.sha256("{\"n\":"+canonical+"}"),McpJson.digest(McpJson.parse("{\"n\":"+source+"}")));
    }

    private static void assertNumberRejected(String source)throws Exception {
        assertRejected(McpJson.parse("{\"n\":"+source+"}"));
    }

    private static void assertRejected(Object value)throws Exception {
        try {
            McpJson.digest(value);
            fail("Expected oversized canonical number rejection");
        } catch(IllegalArgumentException expected) { }
    }
}
