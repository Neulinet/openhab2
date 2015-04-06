// Generated by CoffeeScript 1.9.1
'use strict';
angular.module('ZooLib.controllers.room', []).controller('RoomController', function(itemRepository, $log, $scope, $stateParams, $filter, $state) {
  console.log('RoomController state params: ', $stateParams);
  this.rooms = {};
  this.itemsActive = {};
  this.refreshItems = function(force) {
    return itemRepository.getRooms(force).then((function(_this) {
      return function(rooms) {
        $log.debug("loaded rooms", rooms);
        return _this.rooms = rooms;
      };
    })(this));
  };
  this.refreshItems();
  $scope.$watch('rooms', function(rooms) {
    if (rooms == null) {
      return;
    }
    if (!$stateParams.room && rooms.length > 0) {
      $log.debug("Initial redirect to room " + this.rooms[0].name);
      return $state.go("rooms.room", {
        active: true,
        room: this.rooms[0].name
      });
    }
  }, true);
});
