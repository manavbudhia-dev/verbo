package app.verbo.keyboard

/** Apps you can keep on the shortcut wheel, each shown with its own brand mark. */
object Apps {
    class App(
        val key: String,
        val name: String,
        val brand: Triple<Int, Int, Int>,
        val blurb: String,
        val skill: String? = null,
        val tokenField: String? = null,
        val task: String? = null,
    )

    val ALL = listOf(
        App("notion", "Notion", Brand.notion, "Save the message to your Notion", skill = "Notion", tokenField = "notionToken"),
        App("gmail", "Gmail", Brand.gmail, "Write the email and open a ready draft", skill = "Email"),
        App("outlook", "Outlook", Brand.outlook, "Write the email and open a ready draft", skill = "Email"),
        App("linkedin", "LinkedIn", Brand.linkedin, "Turn the text into a post and open LinkedIn", skill = "LinkedIn post"),
        App("whatsapp", "WhatsApp", Brand.whatsapp, "Send the text to a chat you pick"),
        App("telegram", "Telegram", Brand.telegram, "Send the text to a chat you pick"),
        App("x", "X", Brand.x, "Open a post with your text filled in"),
        App("youtube", "YouTube", Brand.youtube, "Open a search with your text"),
        App("calendar", "Calendar", Brand.calendar, "Open an event with your text filled in"),
        App("slack", "Slack", Brand.slack, "Search your Slack messages", tokenField = "slackToken",
            task = "Search the user's Slack with slack_search for what the text or the copied message asks about. Return a short title line, then up to five numbered lines, most relevant first: 1. message snippet | channel | who | when. If one result is clearly the answer, end with its link on a final [[open:URL]] line. If nothing is found, say so in one line."),
        App("github", "GitHub", Brand.github, "Search issues and pull requests", tokenField = "githubToken",
            task = "Search the user's GitHub with github_search for what the text or the copied message asks about. Return a short title line, then up to five numbered lines, most relevant first: 1. title | repo | state | number. End with the best match's link on a final [[open:URL]] line. If nothing is found, say so in one line."),
        App("todoist", "Todoist", Brand.todoist, "List and add your tasks", tokenField = "todoistToken",
            task = "Use todoist_list_tasks to show the user's tasks that match what the text or the copied message asks about; if it asks to add a task, add it with the Todoist tools you have. Return a short title line then one numbered line per task: 1. task | due | project. If nothing matches, say so in one line."),
        App("linear", "Linear", Brand.linear, "Search your team's issues", tokenField = "linearToken",
            task = "Search the user's Linear with linear_search_issues for what the text or the copied message asks about. Return a short title line, then up to five numbered lines, most relevant first: 1. title | team | state | identifier. End with the best match's link on a final [[open:URL]] line. If nothing is found, say so in one line."),
        App("discord", "Discord", Brand.discord, "Post the text to your server", tokenField = "discordWebhook",
            task = "Send the text, or the copied message if there is one, to the user's Discord with discord_send_message. Reply with exactly one line confirming it was sent, or the error in one line if it failed."),
        App("calendly", "Calendly", Brand.calendly, "Share your scheduling link", tokenField = "calendlyToken",
            task = "Get the user's Calendly scheduling links with calendly_scheduling_links. Reply with one short line naming the best link to share, then that link on a final [[open:URL]] line. If none exist, say so in one line."),
    )

    fun byKey(key: String) = ALL.firstOrNull { it.key == key }
    fun byId(id: String) = if (id.startsWith("app:")) byKey(id.removePrefix("app:")) else null
    fun id(app: App) = "app:" + app.key

    /** The saved token for an app, whichever preference it lives in. */
    fun token(app: App, store: Store): String = when (app.tokenField) {
        null -> ""
        "notionToken" -> store.notionToken
        "calendlyToken" -> store.calendlyToken
        else -> store.token(app.tokenField)
    }
}