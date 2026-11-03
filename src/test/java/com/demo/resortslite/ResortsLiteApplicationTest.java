package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResortsLiteApplicationTest {

    @Test
    void applicationClass_shouldBeInstantiable() {
        ResortsLiteApplication application = new ResortsLiteApplication();

        assertNotNull(application);
    }
}
