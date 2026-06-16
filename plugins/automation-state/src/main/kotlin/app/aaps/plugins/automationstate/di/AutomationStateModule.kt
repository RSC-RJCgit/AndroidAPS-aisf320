package app.aaps.plugins.automationstate.di

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.plugins.automationstate.dialogs.AutomationAddStateDialog
import app.aaps.plugins.automationstate.dialogs.AutomationStateValuesDialog
import app.aaps.plugins.automationstate.services.AutomationStateService
import app.aaps.plugins.automationstate.ui.AutomationStateFragment
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.android.ContributesAndroidInjector
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class AutomationStateModule {

    @ContributesAndroidInjector
    abstract fun contributesAutomationStateFragment(): AutomationStateFragment

    @ContributesAndroidInjector
    abstract fun contributesAutomationAddStateDialog(): AutomationAddStateDialog

    @ContributesAndroidInjector
    abstract fun contributesAutomationStateValuesDialog(): AutomationStateValuesDialog

    companion object {
        @Provides
        @Singleton
        fun provideAutomationStateService(service: AutomationStateService): AutomationStateInterface = service
    }
}
