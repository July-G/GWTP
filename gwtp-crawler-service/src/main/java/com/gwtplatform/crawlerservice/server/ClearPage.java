package com.gwtplatform.crawlerservice.server;

import com.appservicer.server.task.IteratorEntitiesTask;
import com.google.inject.Singleton;
import com.gwtplatform.crawlerservice.server.domain.CachedPage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.googlecode.objectify.ObjectifyService.ofy;

/**
 *
 */
@Singleton
public class ClearPage extends IteratorEntitiesTask<CachedPage> {
    @Override
    protected void batchProcess(List<CachedPage> entities) {
        Date date = new Date();
        List<CachedPage> toDel=new ArrayList<>();
        for (CachedPage entity : entities) {
            if (isDbPageExpired(entity, date)) {
                toDel.add(entity);
            }
        }
        logger.info("deleting "+ toDel.size()+" pages");
        ofy().delete().entities(toDel);
    }

    private boolean isDbPageExpired(CachedPage fetchedPage, Date currDate) {
        //gae urlfetch deadline is 60 seconds
        // If fetch is in progress since more than 60 seconds, we consider something went wrong and fetch again.
        return currDate.getTime() > fetchedPage.getFetchDate().getTime() + CrawlServiceServlet.cachedPageTimeoutSec * 1000;
    }
}
