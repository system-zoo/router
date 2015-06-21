#router

This container is designed to handle the routing of traffic between multiple revisions of the same service. Currently it is capable of supporting a variety of continuous delpoyment techniques including:
  - Blue/Green deployments
  - Canary deployments
  - Mirroring of requests

##Registering a Service

We expect that each container of a service will be registered into the [Consul](https://consul.io/) service catalog. This can be accomplished automatically by using [Registrator](https://github.com/gliderlabs/registrator) or other tool. Currently we expect that each service be named with the following naming convention (accomplished by using the `-e SERVICE_NAME= ` flag when running the docker container`):

  `$serviceName$-$version$-$revision$`

  - **serviceName** [String] - the name of your service (should be unique to your cluster)
  - **version** [Integer] - is meant to indicate the api version of the service. Normally this should only be incremented upon a breaking change of your api. Each new version of a service is essentially treated as an independent service allowing for multiple versions to live side by side with the routing managing individually for each.
  - **revision** [Integer] - the revision of the service/version.  
  
##Controlling the Routing between Revisions

To control the flow of traffic between the different revisions we pull the routing information (as json) from the Consul key/value store at the key `$serviceName$-$version$`.

The format of the routing information is as follows:

```json
{
	"mirrorMode": false,
	"revisions": [
		{ 
			"revision": 1,
			"trafficRatio":0.9,
			"primary": true
		},
		{ 
			"revision": 2,
			"trafficRatio":0.1,
			"primary": false
		}
	]
}
```
  - **mirrorMode** [Boolean] - controls which type of deployment to use. When mirror mode is false the router will split traffic accross the different revisions based off their trafficRatios. When true the router will send ALL traffic to the primary revisions but will also replicate the traffic to the alternate revisions based off the trafficRatio for the non-primary revisions
  - **revision** [Integer] - the revision of this service/version.
  - **trafficRatio** [Double 0-1] - the fraction of traffic to send to this particular revision. The sum of all the trafficRatios should be 1 when mirrorMode is false. In mirrorMode the sum of all the trafficRatios for non-primary revisions should be between 0 and 1.
  - **primary** [Boolean] - used to defined which is the main revision for this service/version. Exactly 1 revisions should have this flag.

This format supports an arbitrary number of revisions for each service/version.

##Usage

`docker run -p 80:80 -d -e "CONSUL_URL=http://192.168.0.1:8500" systemzoo/router`

Parameters (passed in as environmental variables)

 - **CONSUL_URL** - the address of the consul backend to use for service discovery and routing information
 - **MAX_CACHE_SIZE** - *default 10000* the maximum number of services to cache, used to control memory usage
 - **CACHE_TTL** - *default 30* the number of seconds that each entry in the cache will be reused before being updated 
