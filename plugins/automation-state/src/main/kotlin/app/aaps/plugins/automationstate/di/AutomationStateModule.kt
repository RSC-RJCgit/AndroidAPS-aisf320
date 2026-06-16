package app.aaps.plugins.automationstate.di

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.plugins.automationstate.services.AutomationStateService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationStateModule {

    @Binds
    @Singleton
    abstract fun bindAutomationStateService(service: AutomationStateService): AutomationStateInterface
}
