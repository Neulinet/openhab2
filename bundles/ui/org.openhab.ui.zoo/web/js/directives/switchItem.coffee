'use strict'

angular.module('ZooLib.directives.switchItem', []).directive 'switchItem', ($log, itemService, $rootScope) ->

	restrict: 'E'
	replace: yes
	templateUrl: 'partials/directives/switchItem.html'
	scope:
		item: '='
	link: (scope) ->

		scope.local =
			state: null

		scope.options =
			cssIconClass: ''

		updateItem = (newState) ->
			scope.options.cssIconClass = getIconClassByTags(scope.item)
			scope.local.state = newState

		# TODO Make service!
		getIconClassByTags = (item) ->
			return unless item.tags?
			if item.tags.indexOf('power') >= 0 then return 'i-power'
			if item.tags.indexOf('light') >= 0 then return 'i-light-on-small'
			if item.tags.indexOf('fan') >= 0 then return 'i-fan'

		scope.handleChange = ->
			$log.debug "Switch Item #{scope.item.name} changed to #{scope.local.state}"
			itemService.sendCommand itemName: scope.item.name, scope.local.state

		handleBroadcast = (event, newState) ->
			scope.item.state = newState
			updateItem newState
			$rootScope.$broadcast "updateMasterSwitch/#{scope.item.groupNames[0]}"

		scope.$watch 'item', (item) ->
			return unless item?
			updateItem item.state
			scope.$on "smarthome/command/#{item.name}", handleBroadcast
			scope.$on "smarthome/update/#{item.name}", handleBroadcast

		return