package com.doc.docquery.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminFrontendControllerTest {

    @Test
    void fixedAdminEntryForwardsToPackagedIndex() {
        assertThat(new AdminFrontendController().adminIndex())
                .isEqualTo("forward:/admin/index.html");
    }
}
