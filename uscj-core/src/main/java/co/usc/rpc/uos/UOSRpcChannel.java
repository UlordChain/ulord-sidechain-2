package co.usc.rpc.uos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class UOSRpcChannel {
    public static String requestBPList(String rpcUrl, String urlParameters){
        HttpURLConnection connection = null;

        try{
            URL url = new URL(rpcUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type",
                    "application/json");
            connection.setRequestProperty("Content-Length",
                    Integer.toString(urlParameters.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            //Send request
            DataOutputStream wr = new DataOutputStream (
                    connection.getOutputStream());
            wr.writeBytes(urlParameters);
            wr.close();

            //Get Response
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            StringBuilder response = new StringBuilder(); // or StringBuffer if Java version 5+
            String line;
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
            return response.toString();
        }
        catch(Exception ex){
            return "Exception: " + ex.getMessage();
        }
    }

    public static JSONObject getBPSchedule(){

        String rpcUrl = "http://114.67.37.2:20580/v1/chain/get_table_rows";
        String urlParameters = "{\"scope\":\"uosclist\",\"code\":\"uosio\",\"table\":\"uosclist\",\"json\":\"true\"}";
        String bpList = UOSRpcChannel.requestBPList(rpcUrl, urlParameters);

        //JSONObject json = new JSONObject(jsonString);
        JSONArray arr = new JSONObject(bpList).getJSONArray("rows");

        String key = "";

        int totalRounds = 2;
        int totalBPs =  getUniqueBpCount(arr);
        JSONObject rounds = new JSONObject();

        for(int i = 0; i < totalRounds; i++){
            key = "round" + i;
            JSONObject rows = new JSONObject();

            for(int j=i*totalBPs; j< (i*totalBPs) + totalBPs; j++){

                rows.append("rows", arr.getJSONObject(j));
            }
            rounds.put(key,rows);
        }

        //finalList.append("");
        return rounds;
    }

    private static int getUniqueBpCount(JSONArray bpList) {
        List<String> addrArray = new ArrayList<>();
        for(int i = 0; i < bpList.length(); ++i) {
            String addr = bpList.getJSONObject(i).getString("ulord_addr");
            if(!addrArray.contains(addr)) {
                addrArray.add(addr);
            }
        }
        return addrArray.size();
    }

    //TODO remove this function once requestBPList is used in Test/Production
    public static void main(String []args){
        JSONObject obj = getBPSchedule();
        System.out.println(obj);
    }
}
