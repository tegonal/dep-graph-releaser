package ch.loewenfels.depgraph.gui.jobexecution

import kotlin.js.Promise

interface JobExecutor {

    fun trigger(
        jobExecutionData: JobExecutionData,
        jobQueuedHook: (queuedItemUrl: String?) -> Promise<*>,
        jobStartedHook: (buildNumber: Int) -> Promise<*>,
        pollEverySecond: Int,
        maxWaitingTimeForCompletenessInSeconds: Int,
        verbose: Boolean = true
    ): Promise<Pair<AuthData, Int>>

    fun rePollQueueing(
        jobExecutionData: JobExecutionData,
        queuedItemUrl: String,
        jobStartedHook: (buildNumber: Int) -> Promise<*>,
        pollEverySecond: Int,
        maxWaitingTimeForCompletenessInSeconds: Int
    ): Promise<Pair<AuthData, Int>>

    fun rePoll(
        jobExecutionData: JobExecutionData,
        buildNumber: Int,
        pollEverySecond: Int,
        maxWaitingTimeForCompletenessInSeconds: Int
    ): Promise<Pair<AuthData, Int>>

    fun pollAndExtract(
        authData: AuthData,
        url: String,
        regex: Regex,
        pollEverySecond: Int,
        maxWaitingTimeInSeconds: Int,
        errorHandler: (PollTimeoutException) -> Nothing
    ): Promise<String>
}