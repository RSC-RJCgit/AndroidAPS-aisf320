package app.aaps.plugins.automationstate.di

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.plugins.automationstate.services.AutomationStateService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AutomationStateModule {

    @Provides
    @Singleton
    fun provideAutomationStateService(service: AutomationStateService): AutomationStateInterface = service
}
