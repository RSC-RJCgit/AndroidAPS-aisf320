package app.aaps.implementation.bolus

import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.ClientControlActionDispatcher
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/** A read-only client may calculate the bolus wizard as an example, but must never give it (2026-10-16). */
class WizardExecutorImplTest : TestBase() {

    @Mock lateinit var dispatcher: ClientControlActionDispatcher
    @Mock lateinit var nsClient: NsClient
    @Mock lateinit var config: Config
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var masterActionExecutor: MasterActionExecutor
    @Mock lateinit var rh: TextResolver

    @Test fun readOnlyClient_commit_isRefusedAndNothingIsGiven() = runTest {
        whenever(config.AAPSCLIENT).thenReturn(true)
        whenever(preferences.get(BooleanKey.NsClientReadOnlyClient)).thenReturn(true)
        whenever(rh.gs(any<TextRef>())).thenReturn("text")
        val sut = WizardExecutorImpl(RoleBranch(dispatcher, nsClient, config, preferences, rxBus, rh, aapsLogger), masterActionExecutor)

        val result = sut.commit(bolusId = 9L, asAdvisor = false, source = Sources.WizardDialog, label = "l", correctionU = 0.0)

        // 2026-10-22: refused in RoleBranch as an example, with the "example only" message
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.ReadOnlyExample)
        verifyBlocking(masterActionExecutor, never()) { confirm(any(), any(), any(), any(), any(), anyOrNull(), any()) }
        verifyBlocking(dispatcher, never()) { run(any(), any()) }
    }
}
