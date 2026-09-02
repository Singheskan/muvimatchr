package org.example.muvimatchr.voting

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.Session
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "vote",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_vote_session_participant_movie",
            columnNames = ["session_id", "participant_id", "movie_id"],
        )
    ],
)
class Vote(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: Session,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    val participant: Participant,

    @Column(name = "movie_id", nullable = false)
    val movieId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var choice: VoteChoice,

    @Column(name = "voted_at", nullable = false)
    var votedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}

enum class VoteChoice { LIKE, PASS }
