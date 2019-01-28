package co.usc;

import org.ethereum.core.Block;
import org.iq80.leveldb.*;
import static org.fusesource.leveldbjni.JniDBFactory.*;
import java.io.*;

public class Program {
    //0x43160a4636716f7b6db870bf002aeea990db7887d08955461d356f260a3c3b65
    //0x3d63217aa3986d6bbfecec65d8ac8d7a6b556b6ba75b29292b6548f2327a7c45 68
    public static void main(String []args){
        Options options = new Options();
        options.createIfMissing(true);
        DB db = null;

        //byte [] b = new byte[] {-80, -42, 0, -87, -109, -49, 0, 103, -75, -33, -11, -94, -36, 118, 18, 87, 53, -88, -24, 114, 120, -63, -99, -56, -120, 94, 124, 116, -121, -103, -20, 83};
        //byte [] b = new byte[]{61,99,33,122,-93,-104,109,107,-65,-20,-20,101,-40,-84,-115,122,107,85,107,107,-89,91,41,41,43,101,72,-14,50,122,124,69};
        byte [] b = new byte[]{-80, -42, 0, -87,-109,-49,0,103,-75,-33,-11,-94,-36,-11,-94,-36,118,18,87,53,-88,-24,114,120,-63,-99,-56,-120,94,124,116,-121,-103,-20,83};
        try{
            db = factory.open(new File("/home/thgy/work/DPOS/ulord-sidechain-2/database/regtest/blocks"),options);

            //db.put(bytes("Tampa"),bytes("[{\"bpname\":\"marsaccount3\",\"ulord_addr\":\"Ue8JkYfRX7B2gxgMPRRe1efedz9aTwCVX6\",\"bp_valid_time\":1548289560},{\"bpname\":\"uosvegasjack\",\"ulord_addr\":\"Uf1BkNW3mcYBs468B8CDFoPnKVAgDRVc66\",\"bp_valid_time\":1548289566}]"));
            //String value = asString(db.get(bytes("43160a4636716f7b6db870bf002aeea990db7887d08955461d356f260a3c3b65")));
            byte[] value = db.get(b);

            Block bb = new Block(value);
            System.out.println(value);
        }catch(IOException ex){


        }finally{
            try{
                db.close();
            }catch(IOException ex){

            }
        }
    }
}
