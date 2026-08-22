package io.legion.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 基线测试：钉住包根约定（AGENTS.md 统一包根 io.legion），挪动包根会红。 */
class ContractsModuleBaselineTest {

    @Test
    void packageRootIsIoLegionContracts() {
        assertEquals("io.legion.contracts", ContractsModuleBaselineTest.class.getPackageName());
    }
}
