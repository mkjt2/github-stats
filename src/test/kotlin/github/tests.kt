package github

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.math.abs

// TODO add more test coverage

class TestRepo {
    @Test
    fun testContribPct() {
        val repo = Repo("test_repo")
        // forks is not set
        assertFails { repo.contribPct() }
        repo.forks = 0
        // forks is set to 0, division by zero!
        assertFails { repo.contribPct() }
        // forks is set to positive number, but prs still unset
        repo.forks = 2
        assertFails { repo.contribPct() }
        // prs is set to zero, should work now (0%)
        repo.prs = 0
        assertEquals(repo.contribPct(), 0.0.toFloat())
        // prs is set to positive, expect 50% (1/2)
        repo.prs = 1
        assertTrue(abs(repo.contribPct() - 50.0.toFloat()) < 0.1)
    }
}
