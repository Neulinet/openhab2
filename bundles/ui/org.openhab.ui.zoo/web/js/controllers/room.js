// Generated by CoffeeScript 1.9.1
angular.module('ZooLib.controllers.room', []).controller('RoomController', function(itemRepository, $log, $scope, $stateParams, $filter, $state) {
  var TAG_ROOM, activeItemFilter;
  console.log('state params: ', $stateParams);
  TAG_ROOM = 'room';
  activeItemFilter = $filter('activeItems');
  this.rooms = [];
  this.items = {};
  this.refreshItems = function() {
    itemRepository.getAll().then((function(_this) {
      return function(data) {
        var activeItems, items, ref, room;
        data.forEach(function(item) {
          var base, groupName;
          if (item.type === 'GroupItem' && item.tags.indexOf(TAG_ROOM) >= 0) {
            return this.rooms.push(item);
          } else if (item.groupNames.length > 0) {
            if (item.groupNames.length > 1) {
              $log.warn("Item " + item.label + " is in multiple groups, thats not implemented", item.groupNames);
            }
            groupName = item.groupNames[0];
            if ((base = this.items)[groupName] == null) {
              base[groupName] = [];
            }
            return this.items[groupName].push(item);
          }
        }, _this);
        $log.debug('rooms: ', _this.rooms);
        $log.debug('items: ', _this.items);
        ref = _this.items;
        for (room in ref) {
          items = ref[room];
          activeItems = activeItemFilter(items);
          _this.items[room + 'Active'] = activeItems;
        }
        if (!$stateParams.room && _this.rooms.length > 0) {
          $log.debug("Initial redirect to room " + _this.rooms[0].name);
          return $state.go("rooms.room", {
            active: true,
            room: _this.rooms[0].name
          });
        }
      };
    })(this));
  };
  this["switch"] = function(item) {
    return $log.debug('Handle Switch Item', item);
  };
  this.switchMaster = function(room) {
    return $log.debug('Handle Master Switch Item', room);
  };
  this.initialize = function() {
    return this.refreshItems();
  };
  return this.initialize();
});
