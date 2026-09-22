package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession

fun StoredSession.isEduPageParentSession(): Boolean = provider == SchoolProvider.EDU_PAGE &&
    (eduPage?.linkedStudents.orEmpty().size > 1 || eduPage?.userID.orEmpty().contains("parent", ignoreCase = true) ||
        eduPage?.userID.orEmpty().contains("rodic", ignoreCase = true))
