package org.example.muvimatchr.session

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ParticipantRepository : JpaRepository<Participant, UUID>
