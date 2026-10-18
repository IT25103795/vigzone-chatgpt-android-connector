package ai.vigzone.chatgptconnector;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Generation belongs to a system-scheduled job, never to the browser Activity. */
public final class ChatJobService extends JobService {
    @Override public boolean onStartJob(JobParameters parameters) {
        ChatGptConnector.get(this).runScheduled(() -> jobFinished(parameters,false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        ChatGptConnector.get(this).interruptScheduled(parameters.getExtras().getString("requestId",""));
        // Never automatically repeat a possibly billed inference after interruption.
        return false;
    }
}
