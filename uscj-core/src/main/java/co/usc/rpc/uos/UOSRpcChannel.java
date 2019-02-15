package co.usc.rpc.uos;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class UOSRpcChannel {

    private String rpcUrl;
    private String urlParameters;

    public UOSRpcChannel(String url, String port, String urlParameters){

        this.rpcUrl = url + ":" + port + "/v1/chain/get_table_rows";
        this.urlParameters = urlParameters;
        //this.urlParameters = "{\"scope\":\"uosclist\",\"code\":\"uosio\",\"table\":\"uosclist\",\"json\":\"true\"}";
    }

    public String requestBPList(){
        HttpURLConnection connection = null;

        try{
            URL url = new URL(rpcUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
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
            StringBuilder response = new StringBuilder();
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

    public JSONObject getBPSchedule(){

        String bpList = requestBPList();

        JSONArray arr = new JSONObject(bpList).getJSONArray("rows");

        String key = "";

        int totalRounds = 2;
        int totalBPs =  getUniqueBpCount(arr);
        JSONObject rounds = new JSONObject();

        for(int i = 0; i < totalRounds; i++){
            key = "round" + (i + 1);
            JSONObject rows = new JSONObject();

            for(int j=i*totalBPs; j< (i*totalBPs) + totalBPs; j++){

                rows.append("rows", arr.getJSONObject(j));
            }
            rounds.put(key,rows);
        }

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
        String rpcUrl = "https://testrpc1.uosio.org";

        UOSRpcChannel uosRpcChannel = new UOSRpcChannel(rpcUrl, "8250", "{\"scope\":\"uosclist\",\"code\":\"uosio\",\"table\":\"uosclist\",\"json\":\"true\"}");
        JSONObject obj = uosRpcChannel.getBPSchedule();
        System.out.println(obj.getJSONObject("round1").getJSONArray("rows"));
    }
}
