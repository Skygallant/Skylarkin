## Playstore page description

The idea was to have something I could use to base my adventures, from a "Where am I going to charge my car when I get there?" perspective. The app uses the OCM API with static pricing databases, so inaccuracies are to be expected. OSRM API is used to check whether you'd need a ferry to reach the chargepoints, and to drop them from the search if you do. Importantly, the static pricing databases only cover the Island of Ireland (Republic plus Northern), so chargepoint prices elsewhere are not included. All prices are converted to Euro.

This app is also intended to work with Android Auto; a simplified version of the UI will be available on the car's infotainment system. It will automatically plot a destination to the farthest charge point within 50km (50km is currently hardcoded). However, you can select a cardinal direction to give the app a general idea of where to look.

There is also a "Sleep" search available on Android Auto, which will take you to a free overnight carpark/campsite that is open on the day of the search, with an option to restrict the search to locations that have showers available. This search uses the OSM API.

The app's name is inspired by a Mic Christopher album.

View the source code here: https://github.com/Skygallant/Skylarkin.
The app was compiled with the CLI versions of the Android tools, but I'd imagine that Android Studio has an import feature... right?

