package org.example.muvimatchr.voting

import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.ParticipantRepository
import org.example.muvimatchr.session.Session
import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class VoteRepositoryTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var participantRepository: ParticipantRepository

    @Autowired
    lateinit var voteRepository: VoteRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun newParticipant(): Participant {
        val joinCode = UUID.randomUUID().toString().take(16)
        val session = sessionRepository.save(Session(joinCode = joinCode))
        return participantRepository.save(Participant(session = session, displayName = "Voter"))
    }

    private fun voteCount(sessionId: UUID, participantId: UUID, movieId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vote WHERE session_id = ? AND participant_id = ? AND movie_id = ?",
            Int::class.java,
            sessionId,
            participantId,
            movieId,
        )!!

    private fun voteChoice(sessionId: UUID, participantId: UUID, movieId: Long): String =
        jdbcTemplate.queryForObject(
            "SELECT choice FROM vote WHERE session_id = ? AND participant_id = ? AND movie_id = ?",
            String::class.java,
            sessionId,
            participantId,
            movieId,
        )!!

    @Test
    fun `upsertVote for a new tuple inserts exactly one row`() {
        val participant = newParticipant()
        val sessionId = participant.session.id!!
        val participantId = participant.id!!
        val movieId = 550L

        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.LIKE.name)

        assertEquals(1, voteCount(sessionId, participantId, movieId))
    }

    @Test
    fun `upsertVote for an existing tuple updates the choice in place instead of duplicating`() {
        val participant = newParticipant()
        val sessionId = participant.session.id!!
        val participantId = participant.id!!
        val movieId = 551L

        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.LIKE.name)
        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.PASS.name)

        assertEquals(1, voteCount(sessionId, participantId, movieId))
        assertEquals(VoteChoice.PASS.name, voteChoice(sessionId, participantId, movieId))
    }

    @Test
    fun `duplicate vote tuple via plain save is rejected by the database`() {
        val participant = newParticipant()
        val movieId = 552L

        voteRepository.saveAndFlush(
            Vote(session = participant.session, participant = participant, movieId = movieId, choice = VoteChoice.LIKE)
        )

        assertThrows(DataIntegrityViolationException::class.java) {
            voteRepository.saveAndFlush(
                Vote(session = participant.session, participant = participant, movieId = movieId, choice = VoteChoice.PASS)
            )
        }
    }
}
