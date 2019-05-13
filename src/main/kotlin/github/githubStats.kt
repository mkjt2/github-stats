package github

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.DefaultHelpFormatter
import com.xenomachina.argparser.mainBody
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.text.StringBuilder
import mu.KotlinLogging


private val logger = KotlinLogging.logger {}


// Parameters for API querying behaviour
const val GITHUB_API_FAILURE_SLEEP_MS = 2000L
const val GITHUB_API_GRAPHQL_URL = "https://api.github.com/graphql"
const val GITHUB_API_MAX_RESULTS_PER_QUERY = 100
const val GITHUB_MAX_CONSEC_ATTEMPTS = 3
const val GITHUB_OAUTH_TOKEN_ENV_VAR = "GITHUB_OAUTH_TOKEN"


// structure to control how a table column is formatted
class ColSpec(val name: String, val headerTemplate: String, val rowTemplate: String)
// a table consists of a bunch of columns, as defined by HeaderSpec
typealias HeaderSpec = List<ColSpec>

// each record in the table is just a map, mapping column name to column value
typealias Record = Map<String, Any>

// Simple Table formatting infrastructure TODO find a table formatting library
class Table(private val headerSpec: HeaderSpec) {
    private val records: MutableList<Record> = mutableListOf()

    // Format the table as text
    // TODO formatCsv() is a potential extension here
    fun formatTable(): String {
        val fullText = StringBuilder()
        for (colSpec in headerSpec) {
            fullText.append(colSpec.headerTemplate.format(colSpec.name))
        }
        fullText.append("\n" + "-".repeat(fullText.length) + "\n")
        for (r in records) {
            for (colSpec in headerSpec)
                fullText.append(colSpec.rowTemplate.format(r[colSpec.name]))
            fullText.append("\n")
        }
        return fullText.toString()
    }

    // add a record to this table
    fun addRecord(record: Record) {
        // TODO check all cols present in record
        records.add(record)
    }
}

// Something is wrong with environment of this script - e.g. environment variables not right
class EnvironmentError(msg: String) : Exception(msg)

// Entrypoint to the bits of Github API V4 we need
class APIHelper {
    val jsonParser = Parser.default()
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    // Returns a graphQL query string (wrapped in JSON) to be POSTED to the API.
    // The query requests all the metadata and stats we need for all the repos for a given org (with pagination)
    // Pagination is controlled by latestCursor.
    //
    // See https://developer.github.com/v4/ for details.
    private fun createReposInfoQuery(org: String, latestCursor: String): JsonObject {
        val rawQuery = """
            query {
                organization(login: "$org") {
                    repositories(first: $GITHUB_API_MAX_RESULTS_PER_QUERY, after: "$latestCursor") {
                        pageInfo { endCursor hasNextPage }
                        totalCount
                        nodes {
                            name
                            forkCount
                            stargazers { totalCount }
                            pullRequests { totalCount }
                        }
                    }
                }
            }
        """
        logger.debug { "Created query string: $rawQuery" }
        val wrappedQuery = JsonObject()
        wrappedQuery["query"] = rawQuery.trimIndent().replace("\n", "")
        return wrappedQuery
    }

    // For a given org, return relevant counts for all its repos.
    // A JsonArray of JsonObjects is returned.  Each of the JsonObject contains counts for one repo.
    fun doReposInfoQuery(org: String): JsonArray<JsonObject> {
        val token: String = System.getenv(GITHUB_OAUTH_TOKEN_ENV_VAR) ?: ""
        if (token == "")
            throw EnvironmentError("Must define $GITHUB_OAUTH_TOKEN_ENV_VAR to access github API v4")
        var pageNumber = 1
        var fullJsonArray = JsonArray<JsonObject>()
        var consecFails = 0
        var latestCursor = ""
        while (true) {
            logger.info { "Requesting page $pageNumber of $GITHUB_API_MAX_RESULTS_PER_QUERY items." }
            val query = createReposInfoQuery(org, latestCursor)
            val req = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API_GRAPHQL_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "bearer $token")
                    .POST(HttpRequest.BodyPublishers.ofString(query.toJsonString(true)))
                    .build()

            var responseBody = ""
            var jsonObject: JsonObject? = null
            try {
                var response = client.send(req, HttpResponse.BodyHandlers.ofString())
                responseBody = response.body()
                // TODO replace hardcoded status codes - look for constants in java / kotlib stdlib
                if (response.statusCode() != 200) {
                    // TODO revisit exception types in kotlin
                    val msg = "status code = ${response.statusCode()}"
                    if (response.statusCode() == 401 || response.statusCode() == 403)
                        throw EnvironmentError("Is $GITHUB_OAUTH_TOKEN_ENV_VAR set in your environment?")
                    throw Error(msg)
                }
                jsonObject = jsonParser.parse(StringBuilder(responseBody)) as JsonObject
                val repositoriesObj = jsonObject
                        .obj("data")!!
                        .obj("organization")!!
                        .obj("repositories")!!
                val jsonArray = repositoriesObj.array<JsonObject>("nodes")
                if (jsonArray === null)
                    throw Error("Could not parse out nodes array from response body")
                logger.debug { "Nodes retrieved: ${jsonArray.toJsonString(true)}" }
                for (jsonObj in jsonArray)
                    fullJsonArray.add(jsonObj)
                val pageInfoObj = repositoriesObj.obj("pageInfo")!!
                val hasNextPage = pageInfoObj.boolean("hasNextPage")!!
                if (!hasNextPage) break
                latestCursor = pageInfoObj.string("endCursor")!!
                pageNumber++
                consecFails = 0
                continue
            } catch (e: ClassCastException) {
                logger.warn { "Unexpected JSON structure: $responseBody" }
            } catch (e: EnvironmentError) {
                logger.warn(e.toString())
            } catch (e: IOException) {
                logger.warn(e.toString())
            } catch (e: KotlinNullPointerException) {
                logger.warn { "Unexpected JSON sub-structure ${jsonObject!!.toJsonString(true)} " }
            } catch (e: Error) {
                logger.warn(e.toString())
            }
            consecFails += 1
            logger.warn("Failed $consecFails times in a row...")
            if (consecFails > GITHUB_MAX_CONSEC_ATTEMPTS)
                throw Error("Giving up after $consecFails consecutive API failures")
            // TODO can make this exponential back off
            Thread.sleep(GITHUB_API_FAILURE_SLEEP_MS)
        }
        return fullJsonArray

    }
}


class Repo(name: String) {
    val name = name
    var forks = -1
    var stars = -1
    var prs = -1
    var contribPct: () -> Float = {
        if (prs < 0)
            throw Error("cannot calculate contribution percentage: pr count is unset " + prs.toString())
        if (forks < 0)
            throw Error("cannot calculate contribution percentage: fork count is unset " + forks.toString())
        if (forks == 0)
            0.0.toFloat()
        else
            100.toFloat() * prs.toFloat() / forks.toFloat()
    }

    override fun toString(): String {
        return "Repo(name=$name, forks=$forks, stars=$stars, prs=$prs)"
    }
}


abstract class Report {
    private val apiHelper = APIHelper()
    open fun generate(org: String, n: Int): String {
        logger.info("Listing repositories for organization $org")
        val repositories = listRepositories(org)
        val table = createTable(repositories, n)
        return table.formatTable()
    }

    // TODO protected or no?  what is the kotlin way?
    abstract fun createTable(repos: List<Repo>, n: Int): Table

    private fun listRepositories(org: String): List<Repo> {
        val repoList = mutableListOf<Repo>()
        val jsonArray = apiHelper.doReposInfoQuery(org)
        try {
            for (jObj in jsonArray) {
                val repo = Repo(jObj.string("name")!!)
                repo.forks = jObj.int("forkCount")!!
                repo.stars = jObj.obj("stargazers")!!.int("totalCount")!!
                repo.prs = jObj.obj("pullRequests")!!.int("totalCount")!!
                repoList.add(repo)
            }
        } catch (e: KotlinNullPointerException) {
            val msg = "Unexpected JSON record structure"
            logger.error { "$msg $e" }
            throw Error(msg)
        }
        return repoList
    }
}

class ForksReport : Report() {
    override fun createTable(repos: List<Repo>, n: Int): Table {
        val sortedRepos = repos.sortedByDescending { it.forks }
                .subList(0, minOf(repos.size, n))

        val headerSpec = listOf(
                ColSpec("repo", "%-40s", "%-40s"),
                ColSpec("forks", "%10s", "%10d"))
        val table = Table(headerSpec)
        for (repo in sortedRepos)
            table.addRecord(mapOf("repo" to repo.name, "forks" to repo.forks))
        return table
    }
}

class StarsReport : Report() {
    override fun createTable(repos: List<Repo>, n: Int): Table {
        val sortedRepos = repos.sortedByDescending { it.stars }
                .subList(0, minOf(repos.size, n))

        val headerSpec = listOf(
                ColSpec("repo", "%-40s", "%-40s"),
                ColSpec("stars", "%10s", "%10d"))
        val table = Table(headerSpec)
        for (repo in sortedRepos)
            table.addRecord(mapOf("repo" to repo.name, "stars" to repo.stars))
        return table
    }
}

open class PullRequestsReport : Report() {
    override fun createTable(repos: List<Repo>, n: Int): Table {
        val sortedRepos = repos.sortedByDescending { it.prs }
                .subList(0, minOf(repos.size, n))

        val headerSpec = listOf(
                ColSpec("repo", "%-40s", "%-40s"),
                ColSpec("prs", "%10s", "%10d"))
        val table = Table(headerSpec)
        for (repo in sortedRepos)
            table.addRecord(mapOf("repo" to repo.name, "prs" to repo.prs))
        return table
    }
}

class ContribPctReport : Report() {
    override fun createTable(repos: List<Repo>, n: Int): Table {
        val sortedRepos = repos.sortedByDescending { it.contribPct() }
                .subList(0, minOf(repos.size, n))

        val headerSpec = listOf(
                ColSpec("repo", "%-40s", "%-40s"),
                ColSpec("contribPct", "%15s", "%15.2f"))
        val table = Table(headerSpec)
        for (repo in sortedRepos)
            table.addRecord(mapOf("repo" to repo.name, "contribPct" to repo.contribPct()))
        return table
    }
}

enum class ReportType { FORKS, STARS, PRS, CONTRIB_PCT }

class ReportFactory {
    companion object {
        fun create(reportType: ReportType): Report {
            return when (reportType) {
                ReportType.FORKS -> ForksReport()
                ReportType.STARS -> StarsReport()
                ReportType.PRS -> PullRequestsReport()
                ReportType.CONTRIB_PCT -> ContribPctReport()
            }
        }
    }
}

class Args(parser: ArgParser) {
    val topN by parser.storing("--N", help = "Show top N results") { toInt() }
    val org by parser.storing("--org", help = "Github Organization Name")
    val reportType by parser.mapping(
            "--forks" to ReportType.FORKS,
            "--stars" to ReportType.STARS,
            "--prs" to ReportType.PRS,
            "--contrib-pct" to ReportType.CONTRIB_PCT,
            help = "Different report types")
}

fun main(args: Array<String>) = mainBody {
    val helpFormatter = DefaultHelpFormatter(
            prologue = """Show top github.com repos by fork count, star count, pull request count, and contribution rate""",
            epilogue = """In order to access github.com's API, you must obtain an OAuth token:

                $GITHUB_OAUTH_TOKEN_ENV_VAR=<token> githubStats --forks --org google

                https://developer.github.com/v4/guides/forming-calls/#authenticating-with-graphql
            """)
    val parsedArgs = ArgParser(args, helpFormatter = helpFormatter).parseInto(::Args)
    parsedArgs.run { println(ReportFactory.create(reportType).generate(org, topN)) }
}

