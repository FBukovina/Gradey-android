package com.bukovinafilip.gradey.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SchoolUrlNormalizersTest {
    @Test
    fun bakalariNormalizerAddsHttpsAndRemovesLoginPath() {
        assertThat(SchoolURLNormalizer.normalizedBaseURL("demo.bakalari.cz/login"))
            .isEqualTo("https://demo.bakalari.cz")
    }

    @Test
    fun bakalariNormalizerAcceptsCopiedLoginPages() {
        listOf("login", "login.aspx", "next/login", "next/login.aspx").forEach { loginPath ->
            assertThat(SchoolURLNormalizer.normalizedBaseURL("https://school.example/$loginPath"))
                .isEqualTo("https://school.example")
            assertThat(SchoolURLNormalizer.normalizedBaseURL("https://school.example/bakaweb/$loginPath"))
                .isEqualTo("https://school.example/bakaweb")
        }
    }

    @Test
    fun bakalariNormalizerPreservesInstallationPathAndPort() {
        assertThat(SchoolURLNormalizer.normalizedBaseURL(" HTTPS://SCHOOL.EXAMPLE:444/BakaWeb/NeXt/LoGiN.AsPx/?ReturnUrl=%2Fnext#login "))
            .isEqualTo("https://school.example:444/BakaWeb")
    }

    @Test
    fun bakalariNormalizerPreservesEncodedInstallationPath() {
        assertThat(SchoolURLNormalizer.normalizedBaseURL("https://school.example/School%20Portal/login.aspx"))
            .isEqualTo("https://school.example/School%20Portal")
    }

    @Test
    fun bakalariNormalizerLeavesInstallationPathsIntact() {
        listOf("bakaweb", "school/login-tools", "school/login/archive").forEach { installationPath ->
            assertThat(SchoolURLNormalizer.normalizedBaseURL("https://school.example/$installationPath/"))
                .isEqualTo("https://school.example/$installationPath")
        }
    }

    @Test
    fun edupageNormalizerAcceptsSubdomain() {
        assertThat(EduPageURLNormalizer.normalizedBaseURL("myschool"))
            .isEqualTo("https://myschool.edupage.org")
    }
}
