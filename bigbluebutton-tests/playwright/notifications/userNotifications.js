const e = require('../core/elements');
const { MultiUsers } = require("../user/multiusers");
const { ELEMENT_WAIT_LONGER_TIME } = require('../core/constants');

class UserNotifications extends MultiUsers {
  constructor(browser, context) {
    super(browser, context);
  }

  async userLeaveNotifications() {
    await this.modPage.closeAllToastNotifications();
    // User leaves
    await this.modPage.waitAndClick(e.leaveMeetingDropdown, ELEMENT_WAIT_LONGER_TIME)
    await this.modPage.hasElement(e.directLogoutButton, 'should display the leave session button')
    await this.modPage.waitAndClick(e.directLogoutButton, ELEMENT_WAIT_LONGER_TIME);

    // Verify leave notification
    await this.modPage.hasElement(e.meetingEndedModal, ELEMENT_WAIT_LONGER_TIME)
    await this.modPage.hasElement(e.redirectButton, 'should display the redirect button')
    
  }
}

exports.UserNotifications = UserNotifications;
