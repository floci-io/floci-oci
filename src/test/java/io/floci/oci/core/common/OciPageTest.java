package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OciPageTest {

    private static final List<Integer> ITEMS = IntStream.range(0, 25).boxed().toList();

    @Test
    void firstPageAndFollowUpsCoverAllItems() {
        OciPage.Page<Integer> first = OciPage.paginate(ITEMS, 10, null);
        assertEquals(10, first.items().size());
        assertTrue(first.hasNextPage());

        OciPage.Page<Integer> second = OciPage.paginate(ITEMS, 10, first.nextPage());
        assertEquals(10, second.items().size());
        assertEquals(10, second.items().get(0));

        OciPage.Page<Integer> third = OciPage.paginate(ITEMS, 10, second.nextPage());
        assertEquals(5, third.items().size());
        assertFalse(third.hasNextPage());
        assertNull(third.nextPage());
    }

    @Test
    void defaultLimitAppliesWhenAbsent() {
        OciPage.Page<Integer> page = OciPage.paginate(ITEMS, null, null);
        assertEquals(25, page.items().size());
        assertFalse(page.hasNextPage());
    }

    @Test
    void limitIsCappedAtMax() {
        assertEquals(OciPage.MAX_LIMIT, OciPage.normalizeLimit(5000));
    }

    @Test
    void invalidLimitRaisesInvalidParameter() {
        OciException e = assertThrows(OciException.class, () -> OciPage.paginate(ITEMS, 0, null));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void malformedPageTokenRaisesInvalidParameter() {
        OciException e = assertThrows(OciException.class,
                () -> OciPage.paginate(ITEMS, 10, "!!not-base64!!"));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void tokenPastEndReturnsEmptyPage() {
        OciPage.Page<Integer> page = OciPage.paginate(ITEMS, 10, OciPage.encode(100));
        assertTrue(page.items().isEmpty());
        assertNull(page.nextPage());
    }
}
