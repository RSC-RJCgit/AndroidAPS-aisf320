package app.aaps.core.objects.di

import android.content.Context
import android.telephony.SmsManager
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.objects.wizard.DelayedBolusWorker
import dagger.Module
import dagger.Provides
import dagger.android.ContributesAndroidInjector

@Suppress("unused")
@Module(
    includes = [
        CoreModule.Bindings::class,
    ]
)
open class CoreModule {

    @Suppress("unused")
    @Module
    interface Bindings {

        @ContributesAndroidInjector fun bolusWizardInjector(): BolusWizard
        @ContributesAndroidInjector fun quickWizardEntryInjector(): QuickWizardEntry
        @ContributesAndroidInjector fun delayedBolusWorkerInjector(): DelayedBolusWorker
    }

    @Suppress("DEPRECATION")
    @Provides
    fun smsManager(context: Context): SmsManager? = context.getSystemService(SmsManager::class.java)
}