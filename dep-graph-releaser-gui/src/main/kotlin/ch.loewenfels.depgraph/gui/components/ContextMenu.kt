package ch.loewenfels.depgraph.gui.components

import ch.loewenfels.depgraph.ConfigKey
import ch.loewenfels.depgraph.data.*
import ch.loewenfels.depgraph.generateGitCloneCommands
import ch.loewenfels.depgraph.gui.*
import ch.loewenfels.depgraph.gui.jobexecution.ProcessStarter
import ch.loewenfels.depgraph.gui.serialization.ModifiableState
import ch.tutteli.kbox.forEachIn
import ch.tutteli.kbox.mapWithIndex
import kotlinx.html.*
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.browser.document
import kotlin.browser.window
import kotlin.dom.addClass
import kotlin.dom.hasClass
import kotlin.dom.removeClass
import kotlin.reflect.KClass

class ContextMenu(
    private val modifiableState: ModifiableState,
    private val menu: Menu,
    private val processStarter: ProcessStarter?
) {

    fun createProjectContextMenu(div: DIV, project: Project) {
        div.div("contextMenu") {
            val idPrefix = project.id.identifier
            id = "$idPrefix$CONTEXT_MENU_SUFFIX"
            contextMenuEntry(idPrefix, "gitClone", "Git clone", "Show the git clone command for this project",
                iconCreator = { i("material-icons char") { +"G" } }
            ) {
                val releasePlan = modifiableState.releasePlan
                val gitCloneCommand = generateGitCloneCommands(
                    sequenceOf(project),
                    Regex(releasePlan.getConfig(ConfigKey.RELATIVE_PATH_EXCLUDE_PROJECT_REGEX)),
                    Regex(releasePlan.getConfig(ConfigKey.RELATIVE_PATH_TO_GIT_REPO_REGEX)),
                    releasePlan.getConfig(ConfigKey.RELATIVE_PATH_TO_GIT_REPO_REPLACEMENT)
                )
                showOutput("git clone command", gitCloneCommand)
            }
        }
    }

    fun createCommandContextMenu(div: DIV, idPrefix: String, project: Project, index: Int) {
        div.div("contextMenu") {
            id = "$idPrefix$CONTEXT_MENU_SUFFIX"
            commandContextMenuEntry(idPrefix, CONTEXT_MENU_COMMAND_DEACTIVATED, CommandState.Deactivated::class) {
                transitionToDeactivatedIfOk(project, index)
            }
            commandContextMenuEntry(idPrefix, CONTEXT_MENU_COMMAND_SUCCEEDED, CommandState.Succeeded::class) {
                transitionToSucceededIfOk(project, index)
            }
            contextMenuEntry(idPrefix, CONTEXT_MENU_COMMAND_RE_TRIGGER,
                text = "Re-Trigger Command immediately",
                title = "Re-Triggers the Command without waiting until the whole process ends.",
                iconCreator = { i("material-icons") { span()  /* done via css */ } },
                action = { reTriggerProject(project, index) }
            )
        }
    }

    private fun DIV.commandContextMenuEntry(
        idPrefix: String,
        cssClass: String,
        commandClass: KClass<out CommandState>,
        action: (Event) -> Unit
    ) = contextMenuEntry(
        idPrefix, cssClass,
        text = "Set Command to ${commandClass.simpleName}",
        title = "Forcibly sets the state of this command to ${commandClass.simpleName}, to be used with care.",
        iconCreator = { i("material-icons") { span() /* done via css */ } },
        action = action
    )

    private fun DIV.contextMenuEntry(
        idPrefix: String,
        cssClass: String,
        text: String,
        title: String,
        iconCreator: DIV.() -> Unit,
        action: (Event) -> Unit
    ) {
        div(cssClass) {
            id = "$idPrefix$cssClass"
            this.title = title
            iconCreator()
            span { +text }
            val div = getUnderlyingHtmlElement()
            div.addClickEventListener { e ->
                if (!div.hasClass(CSS_DISABLED)) {
                    action(e)
                }
            }
        }
    }

    private fun transitionToDeactivatedIfOk(project: Project, index: Int) {
        val commandState = Pipeline.getCommandState(project.id, index)
        if (isNotInStateToDeactivate(commandState)) return

        Pipeline.getToggle(project, index).click()
    }

    private fun isNotInStateToDeactivate(commandState: CommandState): Boolean {
        return commandState is CommandState.Deactivated ||
            commandState === CommandState.Succeeded ||
            commandState === CommandState.Disabled
    }

    private fun transitionToSucceededIfOk(project: Project, index: Int) {
        if (project.commands[index] is ReleaseCommand) {
            if (notAllOtherCommandsSucceeded(project, index)) {
                val succeeded = CommandState.Succeeded::class.simpleName
                showDialog(
                    "You cannot set this command to the state $succeeded because not all other commands of this project have $succeeded yet." +
                        "\n\n" +
                        "Do you want to set all other commands forcibly to $succeeded as well?"
                ).then { setAllToSucceeded ->
                    if (setAllToSucceeded) {
                        transitionAllCommandsToSucceeded(project)
                        menu.activateSaveButton()
                    }
                }
                return
            }
        }
        transitionToSucceeded(project, index)
        menu.activateSaveButton()
    }

    private fun transitionAllCommandsToSucceeded(project: Project) {
        project.commands.forEachIndexed { index, _ ->
            transitionToSucceeded(project, index)
        }
        val releasePlan = modifiableState.releasePlan
        releasePlan.getSubmodules(project.id).forEach {
            transitionAllCommandsToSucceeded(releasePlan.getProject(it))
        }
    }

    private fun transitionToSucceeded(project: Project, index: Int) {
        Pipeline.changeStateOfCommand(project, index, CommandState.Succeeded, Pipeline.STATE_SUCCEEDED) { _, _ ->
            // we do not check transition here, the user has to know what she does (at least for now)
            CommandState.Succeeded
        }
    }

    private fun notAllOtherCommandsSucceeded(project: Project, index: Int?): Boolean {
        return project.commands.asSequence()
            .mapWithIndex()
            .any { (i, _) ->
                (index == null || i != index) && Pipeline.getCommandState(project.id, i) !== CommandState.Succeeded
            }
            || modifiableState.releasePlan.getSubmodules(project.id)
            .any { notAllOtherCommandsSucceeded(modifiableState.releasePlan.getProject(it), null) }
    }

    private fun reTriggerProject(project: Project, index: Int) {
        val processStarter = if (Pipeline.getTypeOfRun() == TypeOfRun.EXPLORE) {
            App.givenOrFakeProcessStarter(this@ContextMenu.processStarter, modifiableState)
        } else {
            this@ContextMenu.processStarter
        }
        if (processStarter != null) {
            transitionToReadyToReTriggerIfOk(project, index)
            processStarter.reTrigger(project, modifiableState)
        }
    }

    private fun transitionToReadyToReTriggerIfOk(project: Project, index: Int) {
        //verifies that the transition is OK
        Pipeline.changeStateOfCommand(project, index, CommandState.ReadyToReTrigger, Pipeline.STATE_READY_TO_BE_TRIGGER)
    }

    fun setUpOnContextMenuForProjectsAndCommands() {
        val projects = document.querySelectorAll(".project")
            .asList()
            .map { project ->
                Triple(project, (project as HTMLElement).id, { _: String -> })
            }
        val toggleLabels = document.querySelectorAll(".command > .fields > .toggle")
            .asList()
            .map { label ->
                val toggle = label.firstChild as HTMLInputElement
                val idPrefix = toggle.id.substringBefore(Pipeline.DEACTIVATE_SUFFIX)
                Triple(label, idPrefix, ::disableCommandContextEntriesIfNecessary)
            }
        val stateIcons = document.querySelectorAll(".state")
            .asList()
            .map { aNode ->
                val a = aNode as HTMLAnchorElement
                val idPrefix = a.id.substringBefore(Pipeline.STATE_SUFFIX)
                Triple(a, idPrefix, ::disableCommandContextEntriesIfNecessary)
            }
        forEachIn(projects, toggleLabels, stateIcons) { (element, idPrefix, disableContextEntriesIfNecessary) ->
            element.addEventListener("contextmenu", { event ->
                hideAllContextMenus()
                disableContextEntriesIfNecessary(idPrefix)
                val contextMenu = elementById("$idPrefix$CONTEXT_MENU_SUFFIX")
                moveContextMenuPosition(event as MouseEvent, contextMenu)
                contextMenu.style.visibility = "visible"
                window.addEventListener("click", { hideAllContextMenus() }, js("({once: true})"))
                event.preventDefault()
                event.stopPropagation()
            })
        }
        window.addEventListener("contextmenu", { hideAllContextMenus() })
    }

    private fun disableCommandContextEntriesIfNecessary(idPrefix: String) {
        val state = Pipeline.getReleaseState()
        val commandState = Pipeline.getCommandState(idPrefix)
        disableOrEnableContextMenuEntry(
            "$idPrefix$CONTEXT_MENU_COMMAND_DEACTIVATED",
            state == ReleaseState.IN_PROGRESS ||
                isNotInStateToDeactivate(commandState)
        )
        disableOrEnableContextMenuEntry(
            "$idPrefix$CONTEXT_MENU_COMMAND_SUCCEEDED",
            state == ReleaseState.IN_PROGRESS ||
                commandState === CommandState.Succeeded
        )
        disableOrEnableContextMenuEntry(
            "$idPrefix$CONTEXT_MENU_COMMAND_RE_TRIGGER",
            state != ReleaseState.IN_PROGRESS ||
                commandState !== CommandState.Failed,
            "Can only re-trigger when previously failed and process state is in progress."
        )
    }

    /**
     * Disables the given context entry with the given [id] if either
     * [Pipeline.getReleaseState] == [ReleaseState.WATCHING] or if [disable] is true.
     * You can optionally pass a [disabledReason] which is used to explain why the context entry is disabled.
     */
    private fun disableOrEnableContextMenuEntry(
        id: String,
        disable: Boolean,
        disabledReason: String = "Cannot apply this action."
    ) {
        val entry = elementById(id)
        if (Pipeline.getReleaseState() == ReleaseState.WATCHING || disable) {
            entry.setTitleSaveOld(disabledReason)
            entry.addClass(CSS_DISABLED)

        } else {
            val title = entry.getOldTitleOrNull()
            if (title != null) {
                entry.title = title
            }
            entry.removeClass(CSS_DISABLED)
        }
    }

    private fun hideAllContextMenus() {
        document.querySelectorAll(".contextMenu").asList().forEach {
            (it as HTMLElement).style.visibility = "hidden"
        }
    }

    private fun moveContextMenuPosition(event: MouseEvent, contextMenu: HTMLElement) {
        val menuWidth = contextMenu.offsetWidth
        val menuHeight = contextMenu.offsetHeight
        val mouseX = event.pageX
        val mouseY = event.pageY

        val x = if (mouseX + menuWidth > document.body!!.clientWidth + window.scrollX) {
            mouseX - menuWidth
        } else {
            mouseX
        }
        val y = if (mouseY + menuHeight > document.body!!.clientHeight + window.scrollY) {
            mouseY - menuHeight
        } else {
            mouseY
        }
        contextMenu.style.left = "${x}px"
        contextMenu.style.top = "${y}px"
    }

    companion object {
        const val CONTEXT_MENU_SUFFIX = ":contextMenu"
        const val CONTEXT_MENU_COMMAND_DEACTIVATED = "deactivated"
        const val CONTEXT_MENU_COMMAND_SUCCEEDED = "succeeded"
        const val CONTEXT_MENU_COMMAND_RE_TRIGGER = "reTrigger"
        const val CSS_DISABLED = "disabled"
    }
}
