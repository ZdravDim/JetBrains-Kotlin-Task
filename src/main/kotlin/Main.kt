import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
// Data model to represent GitHub repository information
data class GitHubRepo(
    val full_name: String,
    val contents_url: String
)

// Main function
fun main() {
    val token = System.getenv("GITHUB_TOKEN")
    val language = "Java"
    val maxRepos = 1000

    val repositories = getRepositoriesFromGitHub(language, token, maxRepos)

    val classNames = mutableListOf<String>()
    try {
        for (repo in repositories) {
            println("Fetching Java files from ${repo.full_name}")
            try {
                val javaFiles = getJavaFilesFromRepo(repo, token)
                for (javaFile in javaFiles) {
                    println("Processing file: ${javaFile.file_name}")
                    val classNamesInFile = extractClassNames(javaFile.content)
                    classNames.addAll(classNamesInFile)
                }
            val wordCount = countWords(classNames)
            displayPopularWords(wordCount)
            } catch (e: Throwable) {
                println("My Error: ${e.message}")
            }
        }
    } catch (e: Throwable) {
        println("My Error: ${e.message}")
    }

    val wordCount = countWords(classNames)
    displayPopularWords(wordCount)
}

fun getRepositoriesFromGitHub(language: String, token: String, maxRepos: Int): List<GitHubRepo> {
    val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.MINUTES)
        .readTimeout(90, TimeUnit.MINUTES)
        .writeTimeout(90, TimeUnit.MINUTES)
        .build()
    val repos = mutableListOf<GitHubRepo>()
    val perPage = 100  // Max 100 per page (GitHub API limit)
    var page = 1

    while (repos.size < maxRepos) {
        val request = Request.Builder()
            .url("https://api.github.com/search/repositories?q=language:$language&per_page=$perPage&page=$page")
            .header("Authorization", "token $token")
            .build()

        val response = executeWithRetry(client, request)

        val newRepos = parseRepositories(response)
        repos.addAll(newRepos)

        if (newRepos.isEmpty()) break
        page++
        if (repos.size >= maxRepos) break
        Thread.sleep(2000)
    }

    return repos.take(maxRepos)
}

// Execute an HTTP request with retry on 403 (rate limiting)
fun executeWithRetry(client: OkHttpClient, request: Request, maxRetries: Int = 15): String {
    var retries = 0
    var backoff = 2L

    while (retries < maxRetries) {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body!!.string()
            } else if (response.code == 403) {
                println("Received 403 - Rate limit exceeded. Retrying in $backoff seconds...")
                Thread.sleep(backoff * 1000)
                backoff *= 2  // Exponential backoff
                retries++
            } else {
                throw IOException("Unexpected code: ${response.code}")
            }
        }
    }
    throw IOException("Max retries reached for request")
}

fun parseRepositories(json: String): List<GitHubRepo> {
    val jsonElement = Json.parseToJsonElement(json)
    val items = jsonElement.jsonObject["items"]?.jsonArray ?: return emptyList()
    return items.map {
        val fullName = it.jsonObject["full_name"]!!.jsonPrimitive.content
        val contentsUrl = it.jsonObject["contents_url"]!!.jsonPrimitive.content
        GitHubRepo(fullName, contentsUrl)
    }
}

@Serializable
data class GitHubContent(
    val name: String,
    val path: String,
    val type: String,
    val download_url: String?
)

fun getJavaFilesFromRepo(repo: GitHubRepo, token: String, path: String = ""): List<JavaFile> {
    val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.MINUTES)
        .readTimeout(90, TimeUnit.MINUTES)
        .writeTimeout(90, TimeUnit.MINUTES)
        .build()
    val javaFiles = mutableListOf<JavaFile>()

    val contentsUrl = if (path.isEmpty()) {
        repo.contents_url.replace("{+path}", "")
    } else {
        repo.contents_url.replace("{+path}", path)
    }

    val request = Request.Builder()
        .url(contentsUrl)
        .header("Authorization", "token $token")
        .build()

    val response = executeWithRetry(client, request)

    val json = Json { ignoreUnknownKeys = true }
    val contents: List<GitHubContent> = json.decodeFromString(response)

    for (content in contents) {
        if (content.type == "file" && content.name.endsWith(".java")) {
            println(content.name)
            // If it's a Java file, fetch its content
            val fileContentRequest = Request.Builder()
                .url(content.download_url!!)
                .header("Authorization", "token $token")
                .build()

            val fileResponse = executeWithRetry(client, fileContentRequest)
            javaFiles.add(JavaFile(content.name, fileResponse))

        } else if (content.type == "dir") {
            // If it's a directory, recursively fetch its contents
            val subDirFiles = getJavaFilesFromRepo(repo, token, content.path)  // Recursive call
            javaFiles.addAll(subDirFiles)
        } else {
            println("skipped: ${content.name}")
        }
    }

    return javaFiles
}

@Serializable
// Data model to represent Java file content
data class JavaFile(
    val file_name: String,
    val content: String
)

fun extractClassNames(javaCode: String): List<String> {
    val classRegex = Regex("""class\s+([A-Za-z0-9_]+)""")
    return classRegex.findAll(javaCode).map { it.groupValues[1] }.toList()
}

fun splitCamelCase(className: String): List<String> {
    return className.split("(?<=[a-z])(?=[A-Z])".toRegex())
}

fun countWords(classNames: List<String>): Map<String, Int> {
    val wordCount = mutableMapOf<String, Int>()

    for (className in classNames) {
        val words = splitCamelCase(className)
        for (word in words) {
            wordCount[word] = wordCount.getOrDefault(word, 0) + 1
        }
    }

    return wordCount
}

fun displayPopularWords(wordCount: Map<String, Int>) {
    println("Most popular words in class names:")
    wordCount.toList().sortedByDescending { (_, value) -> value }./*take(100).*/forEach { (word, count) ->
        println("$word: $count")
    }
}
