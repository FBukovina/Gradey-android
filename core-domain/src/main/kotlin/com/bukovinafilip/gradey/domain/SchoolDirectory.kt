package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.SchoolDirectoryMunicipality
import com.bukovinafilip.gradey.model.SchoolDirectorySchool

interface SchoolDirectoryClient {
    suspend fun fetchMunicipalities(): List<SchoolDirectoryMunicipality>
    suspend fun fetchSchools(municipalityName: String): List<SchoolDirectorySchool>
}

interface SchoolDirectoryProvider {
    suspend fun cachedSchools(): List<SchoolDirectorySchool>
    suspend fun refreshDirectory(): List<SchoolDirectorySchool>
}
