package tech.iresponse.affiliate;

import java.util.List;
import org.json.JSONArray;
import tech.iresponse.models.affiliate.Offer;

public interface AffiliateInterface {

    public void getOffers(List<Integer> offersIds) throws Exception;
    public String getSuppressionLink(Offer offer) throws Exception;
    public JSONArray getClicks(String startDate,String endDate) throws Exception;
    public JSONArray getConversions(String startDate,String endDate) throws Exception;
}
