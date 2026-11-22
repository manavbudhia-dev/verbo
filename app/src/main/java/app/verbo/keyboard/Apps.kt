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
        /** The tap sends or saves the text somewhere else, so the field is cleared and a status line confirms. */
        val sends: Boolean = false,
    )

    /**
     * One tap from the wheel or a held key does the app's own job with the text (or the copied message):
     * a send or a save happens and a status line says so, with Open where there is something to see.
     * Searching across apps stays with the "Find anything" act.
     */
    val ALL = listOf(
        App("notion", "Notion", Brand.notion, "Save the text or copied message as a Notion page", skill = "Save to Notion", tokenField = "notionToken"),
        App("gmail", "Gmail", Brand.gmail, "Write the email and open the ready draft", skill = "Email"),
        App("outlook", "Outlook", Brand.outlook, "Write the email and open the ready draft", skill = "Email"),
        App("linkedin", "LinkedIn", Brand.linkedin, "Write the post and open LinkedIn with it", skill = "LinkedIn post"),
        App("whatsapp", "WhatsApp", Brand.whatsapp, "Send the text to a chat you pick"),
        App("telegram", "Telegram", Brand.telegram, "Send the text to a chat you pick"),
        App("x", "X", Brand.x, "Open a post with your text filled in"),
        App("youtube", "YouTube", Brand.youtube, "Open a search with your text"),
        App("calendar", "Calendar", Brand.calendar, "Open an event with your text filled in"),
        App("slack", "Slack", Brand.slack, "Send the text to Slack", tokenField = "slackToken", sends = true,
            task = "Send the user's text to Slack with slack_send_message, word for word (if they typed nothing, send the copied message). " +
                "Post it to the channel or person the text names (like #design or \"to Priya\"), removing that address from the message; " +
                "if none is named, find the team's general channel with slack_list_channels and post there. " +
                "Reply with exactly one line: Sent to #CHANNEL, or the error in one line. If Slack returns a link to the message, end with [[open:URL]]."),
        App("github", "GitHub", Brand.github, "Turn the text into an issue", tokenField = "githubToken", sends = true,
            task = "Create a GitHub issue from the user's text (or the copied message) with github_create_issue. Use the repository the text names; " +
                "if none is named, use the user's most recently updated repository from github_my_repos. A short title, the details as the body. " +
                "Reply with exactly one line: Created issue #NUMBER in OWNER/REPO, then [[open:ISSUE_URL]] on a final line. If it fails, say why in one line."),
        App("todoist", "Todoist", Brand.todoist, "Add the text as a task", tokenField = "todoistToken", sends = true,
            task = "Add the user's text (or the copied message) to Todoist as a task with todoist_add_task: a short task name, and a due date if the text gives one " +
                "(like tomorrow 5pm). Reply with exactly one line: Added to Todoist: TASK (due WHEN). If it fails, say why in one line."),
        App("linear", "Linear", Brand.linear, "Turn the text into an issue", tokenField = "linearToken", sends = true,
            task = "Create a Linear issue from the user's text (or the copied message) with linear_create_issue: a short title and the details as the description, " +
                "in the team the text names or the user's first team. Reply with exactly one line: Created IDENTIFIER: TITLE, then [[open:ISSUE_URL]] on a final line. " +
                "If it fails, say why in one line."),
        App("discord", "Discord", Brand.discord, "Post the text to your server", tokenField = "discordWebhook", sends = true,
            task = "Send the user's text, word for word (or the copied message if they typed nothing), to their Discord with discord_send_message. " +
                "Reply with exactly one line: Sent to Discord, or the error in one line."),
        App("calendly", "Calendly", Brand.calendly, "Put your scheduling link in the message", tokenField = "calendlyToken",
            task = "Get the user's Calendly scheduling links with calendly_scheduling_links and pick the best one for the text. Reply with the user's own text " +
                "if they typed any, then the link on its own line, ready to send. Nothing else. If there is no link, say so in one line."),
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