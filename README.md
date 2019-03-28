## Welcome to Ulord-Sidechain-2

## About
Ulord-Sidechain-2 (USC-2) is the improvement of its ancestors USC. The USC-2 provides the same value and functionality to the Ulord ecosystem from its ancestor. In addition, the USC-2 also improves over USC by implementing PBFT like consensus algorithm. Transactions in USC-2 can achieve finality as soon as the block has reached irreversibility i.e. 2/3 + 1 Block Producers produces blocks on top of the block containing this transaction, hence achieving deterministic transaction finality. Moreover, USC-2 implements Drivechain technology for faster 2-way pegging operations, moving funds from one chain to other and vice versa is much faster than ever.


## Getting Started
To get started with USC-2 you can follow the same detailed guide as USC which can be found here https://github.com/UlordChain/Ulord-Sidechain/wiki

Or if you want to get started in minutes follow the below steps.
1. Download and install [Java 8 JDK](http://www.webupd8.org/2012/09/install-oracle-java-8-in-ubuntu-via-ppa.html)
2. Download the latest release of [USC-2](https://github.com/UlordChain/ulord-sidechain-2/releases)
3. Extract USC-2.tar.gz
``` 
  tar -xvzf USC-2.tar.gz
```
4. Run configure.sh
``` 
  cd USC-2/; ./configure.sh
```
5. Run start.sh
```
  ./start.sh
```
To check log you can run
```
tail -f logs/usc.log
```

To check the block height, run the following command
```
// Port 5288 for Testnet and 5188 for Mainnet.
curl -X POST -H "Content-Type:application/json" -d '{"method":"eth_getBlockByNumber","params":["latest", false],"json_rpc":"2.0", "id":1234}' localhost:5288
```


## License
USC2 is licensed under the GNU Lesser General Public License v3.0, also included in our repository in the [COPYING.LESSER](https://github.com/UlordChain/Ulord-Sidechain/blob/master/COPYING.LESSER) file.
